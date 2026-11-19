package uk.gov.hmcts.cp.notification.task;

import static java.lang.String.format;
import static java.util.List.copyOf;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.LogField.CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.LogField.DEPENDENCY;
import static uk.gov.hmcts.cp.notification.logging.LogField.OPERATION;
import static uk.gov.hmcts.cp.notification.logging.LogField.OUTCOME;
import static uk.gov.hmcts.cp.notification.logging.LogField.REASON;
import static uk.gov.hmcts.cp.notification.logging.LogField.STATUS_CODE;
import static uk.gov.hmcts.cp.notification.task.RetryCause.GOV_NOTIFY_SEND;
import static uk.gov.hmcts.cp.notification.task.RetryCause.MATERIAL_DOWNLOAD;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.logging.CallOutcome;
import uk.gov.hmcts.cp.notification.logging.Dependency;
import uk.gov.hmcts.cp.notification.logging.FailureReason;
import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;
import uk.gov.hmcts.cp.notification.logging.Operation;
import uk.gov.hmcts.cp.notification.logging.OutcomeReason;
import uk.gov.hmcts.cp.notification.logging.OutcomeReasonClassifier;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentDownloader;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.sender.GovNotifyClient;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.GovNotifyFailureClassifier;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Downloads the letter's PDF through Material and sends it to Gov.Notify as a precompiled letter (legacy
 * {@code send-letter}). On success the letter's status is polled by {@link CheckLetterStatusTask}.
 */
@Task(SendLetterTask.TASK_NAME)
@Component
public class SendLetterTask implements ExecutableTask {
    public static final String TASK_NAME = "SEND_LETTER";

    private final Logger logger = getLogger(SendLetterTask.class);

    private final MaterialAttachmentDownloader materialAttachmentDownloader;
    private final GovNotifyClient govNotifyClient;
    private final TransientFailurePolicy failurePolicy;
    private final FailedNotificationRecorder failedNotificationRecorder;
    private final TaskRetrySchedule taskRetrySchedule;
    private final ExecutionService executionService;
    private final CpTaskFactory taskFactory;
    private final GovNotifyFailureClassifier govNotifyFailureClassifier;
    private final FailureReasonClassifier failureReasonClassifier;
    private final OutcomeReasonClassifier outcomeReasonClassifier;
    private final List<Long> retryDurationsSecs;

    public SendLetterTask(
            final MaterialAttachmentDownloader materialAttachmentDownloader,
            final GovNotifyClient govNotifyClient,
            final TransientFailurePolicy failurePolicy,
            final FailedNotificationRecorder failedNotificationRecorder,
            final TaskRetrySchedule taskRetrySchedule,
            final ExecutionService executionService,
            final CpTaskFactory taskFactory,
            final GovNotifyFailureClassifier govNotifyFailureClassifier,
            final FailureReasonClassifier failureReasonClassifier,
            final OutcomeReasonClassifier outcomeReasonClassifier,
            @Value("${cp.notification.retry.letter-durations-secs}") final List<Long> retryDurationsSecs) {
        this.materialAttachmentDownloader = materialAttachmentDownloader;
        this.govNotifyClient = govNotifyClient;
        this.failurePolicy = failurePolicy;
        this.failedNotificationRecorder = failedNotificationRecorder;
        this.taskRetrySchedule = taskRetrySchedule;
        this.executionService = executionService;
        this.taskFactory = taskFactory;
        this.govNotifyFailureClassifier = govNotifyFailureClassifier;
        this.failureReasonClassifier = failureReasonClassifier;
        this.outcomeReasonClassifier = outcomeReasonClassifier;
        this.retryDurationsSecs = copyOf(retryDurationsSecs);
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final LetterJob letter = LetterJob.from(executionInfo.getJobData());
        ExecutionInfo result;
        try {
            final byte[] pdf = materialAttachmentDownloader.download(letter.letterUrl(), letter.notificationId()).content();
            result = send(letter, pdf, executionInfo);
        } catch (final MaterialAttachmentException materialAttachmentException) {
            result = downloadFailed(letter, materialAttachmentException, executionInfo);
        }
        return result;
    }

    private ExecutionInfo send(final LetterJob letter, final byte[] pdf, final ExecutionInfo executionInfo) {
        ExecutionInfo result;
        try {
            final UUID notificationId = letter.notificationId();
            final boolean firstClass = letter.firstClass();
            logSending(notificationId, firstClass);
            final String reference = govNotifyClient.sendPrecompiledLetter(notificationId, pdf, firstClass);
            logSent(notificationId, reference);
            executionService.executeWith(taskFactory.createCheckLetterStatusJob(letter, reference));
            result = taskRetrySchedule.completed(executionInfo);
        } catch (final GovNotifyException govNotifyException) {
            final Optional<Integer> httpStatusCode = govNotifyException.getHttpStatusCode();
            if (govNotifyFailureClassifier.isTemporary(govNotifyException)) {
                final TransientFailure transientFailure = new TransientFailure(
                        letter.notificationId(),
                        GOV_NOTIFY_SEND,
                        LETTER,
                        httpStatusCode,
                        govNotifyException,
                        "Gov.Notify letter send did not recover within the retry window");
                result = failurePolicy.retryOrFail(transientFailure, executionInfo, retryDurationsSecs);
            } else {
                final Optional<FailureReason> failureReason =
                        failureReasonClassifier.classify(httpStatusCode, govNotifyException);
                final UUID notificationId = letter.notificationId();
                logSendFailedPermanently(notificationId, httpStatusCode, failureReason);
                final OutcomeReason outcomeReason = outcomeReasonClassifier.providerFailed(failureReason);
                final NotificationFailure notificationFailure = new NotificationFailure(
                        notificationId,
                        LETTER,
                        govNotifyException.getHttpStatus(),
                        govNotifyException.getMessage(),
                        outcomeReason);
                result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
            }
        }
        return result;
    }

    private ExecutionInfo downloadFailed(
            final LetterJob letter,
            final MaterialAttachmentException materialAttachmentException,
            final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        final Optional<Integer> statusCode = ofNullable(materialAttachmentException.getStatusCode());
        if (materialAttachmentException.isRetryable()) {
            final TransientFailure transientFailure = new TransientFailure(
                    letter.notificationId(),
                    MATERIAL_DOWNLOAD,
                    LETTER,
                    statusCode,
                    materialAttachmentException,
                    "Material letter download did not recover within the retry window");
            result = failurePolicy.retryOrFail(transientFailure, executionInfo, retryDurationsSecs);
        } else {
            final UUID notificationId = letter.notificationId();
            final String failureMessage = materialAttachmentException.getMessage();
            logDownloadFailedPermanently(notificationId, statusCode, failureMessage);
            final OutcomeReason outcomeReason = outcomeReasonClassifier.attachmentFailed(statusCode);
            final NotificationFailure notificationFailure = new NotificationFailure(
                    notificationId,
                    LETTER,
                    materialAttachmentException.getStatusCode(),
                    failureMessage,
                    outcomeReason);
            result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
        }
        return result;
    }

    private void logSending(final UUID notificationId, final boolean firstClass) {
        final String message = format("Sending letter for notification '%s' (first class: '%s')", notificationId, firstClass);
        logger.info(message);
    }

    private void logSent(final UUID notificationId, final String reference) {
        final String message = format(
                "Sent letter for notification '%s' via Gov.Notify (reference '%s')", notificationId, reference);
        logger.atInfo()
                .addKeyValue(DEPENDENCY, Dependency.GOV_UK_NOTIFY.getFieldValue())
                .addKeyValue(OPERATION, Operation.SEND.getFieldValue())
                .addKeyValue(CHANNEL, LETTER.getFieldValue())
                .addKeyValue(OUTCOME, CallOutcome.SUCCEEDED.getFieldValue())
                .log(message);
    }

    /** {@code failureReason} says why when Gov.Notify gave no HTTP response, so the outage alert can count it. */
    private void logSendFailedPermanently(
            final UUID notificationId, final Optional<Integer> statusCode, final Optional<FailureReason> failureReason) {
        final Optional<String> statusCodeText = statusCode.map(String::valueOf);
        final String message = format(
                "Permanent letter send failure for notification '%s' (http '%s') — marking FAILED",
                notificationId, statusCodeText.orElse("none"));
        final LoggingEventBuilder loggingEventBuilder = logger.atWarn()
                .addKeyValue(DEPENDENCY, Dependency.GOV_UK_NOTIFY.getFieldValue())
                .addKeyValue(OPERATION, Operation.SEND.getFieldValue())
                .addKeyValue(CHANNEL, LETTER.getFieldValue())
                .addKeyValue(OUTCOME, CallOutcome.PERMANENT_FAILURE.getFieldValue());
        statusCode.ifPresent(code -> loggingEventBuilder.addKeyValue(STATUS_CODE, code));
        failureReason.ifPresent(reason -> loggingEventBuilder.addKeyValue(REASON, reason.getFieldValue()));
        loggingEventBuilder.log(message);
    }

    private void logDownloadFailedPermanently(
            final UUID notificationId, final Optional<Integer> statusCode, final String failureMessage) {
        final String message = format(
                "Permanent Material letter download failure for notification '%s' ('%s') — marking FAILED, no retry",
                notificationId, failureMessage);
        final LoggingEventBuilder loggingEventBuilder = logger.atWarn()
                .addKeyValue(DEPENDENCY, Dependency.MATERIAL.getFieldValue())
                .addKeyValue(OPERATION, Operation.DOWNLOAD.getFieldValue())
                .addKeyValue(CHANNEL, LETTER.getFieldValue())
                .addKeyValue(OUTCOME, CallOutcome.PERMANENT_FAILURE.getFieldValue());
        statusCode.ifPresent(code -> loggingEventBuilder.addKeyValue(STATUS_CODE, code));
        loggingEventBuilder.log(message);
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return of(retryDurationsSecs);
    }
}
