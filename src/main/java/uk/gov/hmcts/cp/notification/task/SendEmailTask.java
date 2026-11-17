package uk.gov.hmcts.cp.notification.task;

import static java.lang.String.format;
import static java.util.List.copyOf;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static org.slf4j.LoggerFactory.getLogger;
import static org.springframework.util.StringUtils.hasText;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.LogField.CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.LogField.DEPENDENCY;
import static uk.gov.hmcts.cp.notification.logging.LogField.OPERATION;
import static uk.gov.hmcts.cp.notification.logging.LogField.OUTCOME;
import static uk.gov.hmcts.cp.notification.logging.LogField.REASON;
import static uk.gov.hmcts.cp.notification.logging.LogField.STATUS_CODE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;
import static uk.gov.hmcts.cp.notification.task.RetryCause.ATTACHMENT_DOWNLOAD;
import static uk.gov.hmcts.cp.notification.task.RetryCause.GOV_NOTIFY_SEND;
import static uk.gov.hmcts.cp.notification.task.RetryCause.MATERIAL_DOWNLOAD;
import static uk.gov.hmcts.cp.notification.task.RetryCause.OFFICE365_SEND;

import com.azure.storage.blob.models.BlobStorageException;
import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import uk.gov.hmcts.cp.notification.blob.AttachmentDownloader;
import uk.gov.hmcts.cp.notification.blob.PermanentBlobException;
import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.logging.FailureReason;
import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;
import uk.gov.hmcts.cp.notification.logging.OutcomeReason;
import uk.gov.hmcts.cp.notification.logging.OutcomeReasonClassifier;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentDownloader;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.sender.Attachment;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.notification.sender.EmailSender;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.GovNotifyFailureClassifier;
import uk.gov.hmcts.cp.notification.sender.Office365Exception;
import uk.gov.hmcts.cp.notification.sender.SendResult;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Task(SendEmailTask.TASK_NAME)
@Component
// Each log line writes its field values out in full, so a search for a value the alerts read finds every line that
// logs it.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class SendEmailTask implements ExecutableTask {
    public static final String TASK_NAME = "SEND_EMAIL";

    private final Logger logger = getLogger(SendEmailTask.class);

    private final AttachmentDownloader attachmentDownloader;
    private final MaterialAttachmentDownloader materialAttachmentDownloader;
    private final EmailSender emailSender;
    private final TransientFailurePolicy failurePolicy;
    private final FailedNotificationRecorder failedNotificationRecorder;
    private final TaskRetrySchedule taskRetrySchedule;
    private final ExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final CpTaskFactory taskFactory;
    private final GovNotifyFailureClassifier govNotifyFailureClassifier;
    private final FailureReasonClassifier failureReasonClassifier;
    private final OutcomeReasonClassifier outcomeReasonClassifier;
    private final List<Long> retryDurationsSecs;

    public SendEmailTask(
            final AttachmentDownloader attachmentDownloader,
            final MaterialAttachmentDownloader materialAttachmentDownloader,
            final EmailSender emailSender,
            final TransientFailurePolicy failurePolicy,
            final FailedNotificationRecorder failedNotificationRecorder,
            final TaskRetrySchedule taskRetrySchedule,
            final ExecutionService executionService,
            final ObjectMapper objectMapper,
            final CpTaskFactory taskFactory,
            final GovNotifyFailureClassifier govNotifyFailureClassifier,
            final FailureReasonClassifier failureReasonClassifier,
            final OutcomeReasonClassifier outcomeReasonClassifier,
            @Value("${cp.notification.retry.email-durations-secs}") final List<Long> retryDurationsSecs) {
        this.attachmentDownloader = attachmentDownloader;
        this.materialAttachmentDownloader = materialAttachmentDownloader;
        this.emailSender = emailSender;
        this.failurePolicy = failurePolicy;
        this.failedNotificationRecorder = failedNotificationRecorder;
        this.taskRetrySchedule = taskRetrySchedule;
        this.executionService = executionService;
        this.objectMapper = objectMapper;
        this.taskFactory = taskFactory;
        this.govNotifyFailureClassifier = govNotifyFailureClassifier;
        this.failureReasonClassifier = failureReasonClassifier;
        this.outcomeReasonClassifier = outcomeReasonClassifier;
        this.retryDurationsSecs = copyOf(retryDurationsSecs);
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final SendEmailCommand command = objectMapper.readValue(
                executionInfo.getJobData().toString(), SendEmailCommand.class);
        ExecutionInfo result;
        try {
            result = send(command, downloadAttachment(command), executionInfo);
        } catch (final PermanentBlobException permanentBlobException) {
            final Optional<Integer> statusCode = ofNullable(permanentBlobException.getStatusCode());
            final UUID notificationId = command.notificationId();
            logAttachmentFailedPermanently(notificationId, statusCode);
            final OutcomeReason outcomeReason = outcomeReasonClassifier.attachmentFailed(statusCode);
            final NotificationFailure notificationFailure = new NotificationFailure(
                    notificationId,
                    EMAIL,
                    permanentBlobException.getStatusCode(),
                    permanentBlobException.getMessage(),
                    outcomeReason);
            result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
        } catch (final BlobStorageException blobStorageException) {
            final int httpStatusCode = blobStorageException.getStatusCode();
            final TransientFailure transientFailure = new TransientFailure(
                    command.notificationId(),
                    ATTACHMENT_DOWNLOAD,
                    EMAIL,
                    of(httpStatusCode),
                    blobStorageException,
                    "Attachment download did not recover within the retry window");
            result = failurePolicy.retryOrFail(transientFailure, executionInfo, retryDurationsSecs);
        } catch (final MaterialAttachmentException materialAttachmentException) {
            result = materialAttachmentFailed(command, materialAttachmentException, executionInfo);
        }
        return result;
    }

    private ExecutionInfo materialAttachmentFailed(
            final SendEmailCommand command,
            final MaterialAttachmentException materialAttachmentException,
            final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        final Optional<Integer> statusCode = ofNullable(materialAttachmentException.getStatusCode());
        if (materialAttachmentException.isRetryable()) {
            final TransientFailure transientFailure = new TransientFailure(
                    command.notificationId(),
                    MATERIAL_DOWNLOAD,
                    EMAIL,
                    statusCode,
                    materialAttachmentException,
                    "Material attachment download did not recover within the retry window");
            result = failurePolicy.retryOrFail(transientFailure, executionInfo, retryDurationsSecs);
        } else {
            final UUID notificationId = command.notificationId();
            final String failureMessage = materialAttachmentException.getMessage();
            logMaterialAttachmentFailedPermanently(notificationId, statusCode, failureMessage);
            final OutcomeReason outcomeReason = outcomeReasonClassifier.attachmentFailed(statusCode);
            final NotificationFailure notificationFailure = new NotificationFailure(
                    notificationId,
                    EMAIL,
                    materialAttachmentException.getStatusCode(),
                    failureMessage,
                    outcomeReason);
            result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
        }
        return result;
    }

    // Precedence (legacy parity): a non-blank materialUrl wins over fileUri; a blank one counts as absent.
    private Attachment downloadAttachment(final SendEmailCommand command) {
        Attachment attachment = null;
        if (hasText(command.materialUrl())) {
            attachment = materialAttachmentDownloader.download(command.materialUrl(), command.notificationId());
        } else if (hasText(command.fileUri())) {
            attachment = Attachment.withoutFilename(attachmentDownloader.download(command.fileUri()));
        }
        return attachment;
    }

    private ExecutionInfo send(
            final SendEmailCommand command, final Attachment attachment, final ExecutionInfo executionInfo) {
        ExecutionInfo result;
        try {
            final SendResult sendResult = emailSender.sendEmail(command, attachment);
            // Office 365 has already sent the email, so SENT is recorded by its own task (legacy: mark-as-sent
            // command, no poll); Gov.Notify is polled. Either job commits with this task's completion.
            executionService.executeWith(sendResult.sentOnSubmit()
                    ? taskFactory.createRecordSentJob(command, sendResult)
                    : taskFactory.createCheckStatusJob(command, sendResult));
            result = taskRetrySchedule.completed(executionInfo);
        } catch (final Office365Exception office365Exception) {
            result = office365SendFailed(command, office365Exception, executionInfo);
        } catch (final GovNotifyException govNotifyException) {
            final Optional<Integer> httpStatusCode = govNotifyException.getHttpStatusCode();
            if (govNotifyFailureClassifier.isTemporary(govNotifyException)) {
                final TransientFailure transientFailure = new TransientFailure(
                        command.notificationId(),
                        GOV_NOTIFY_SEND,
                        EMAIL,
                        httpStatusCode,
                        govNotifyException,
                        "Gov.Notify send did not recover within the retry window");
                result = failurePolicy.retryOrFail(transientFailure, executionInfo, retryDurationsSecs);
            } else {
                final Optional<FailureReason> failureReason =
                        failureReasonClassifier.classify(httpStatusCode, govNotifyException);
                final UUID notificationId = command.notificationId();
                logGovNotifySendFailedPermanently(notificationId, httpStatusCode, failureReason);
                final OutcomeReason outcomeReason = outcomeReasonClassifier.providerFailed(failureReason);
                final NotificationFailure notificationFailure = new NotificationFailure(
                        notificationId,
                        EMAIL,
                        govNotifyException.getHttpStatus(),
                        govNotifyException.getMessage(),
                        outcomeReason);
                result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
            }
        }
        return result;
    }

    private ExecutionInfo office365SendFailed(
            final SendEmailCommand command, final Office365Exception office365Exception, final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        final Optional<Integer> statusCode = ofNullable(office365Exception.getHttpStatus());
        if (office365Exception.isPermanent()) {
            final UUID notificationId = command.notificationId();
            logOffice365SendFailedPermanently(notificationId, statusCode);
            final NotificationFailure notificationFailure = new NotificationFailure(
                    notificationId,
                    EMAIL,
                    office365Exception.getHttpStatus(),
                    office365Exception.getMessage(),
                    PROVIDER_REJECTED);
            result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
        } else {
            final TransientFailure transientFailure = new TransientFailure(
                    command.notificationId(),
                    OFFICE365_SEND,
                    EMAIL,
                    statusCode,
                    office365Exception,
                    "Office 365 send did not recover within the retry window");
            result = failurePolicy.retryOrFail(transientFailure, executionInfo, retryDurationsSecs);
        }
        return result;
    }

    private void logAttachmentFailedPermanently(final UUID notificationId, final Optional<Integer> statusCode) {
        final String message = format(
                "Permanent attachment failure for notification '%s' — marking FAILED, no retry", notificationId);
        final LoggingEventBuilder loggingEventBuilder = logger.atWarn()
                .addKeyValue(DEPENDENCY, "blob-storage")
                .addKeyValue(OPERATION, "download")
                .addKeyValue(CHANNEL, "email")
                .addKeyValue(OUTCOME, "permanent-failure");
        statusCode.ifPresent(code -> loggingEventBuilder.addKeyValue(STATUS_CODE, code));
        loggingEventBuilder.log(message);
    }

    private void logMaterialAttachmentFailedPermanently(
            final UUID notificationId, final Optional<Integer> statusCode, final String failureMessage) {
        final String message = format(
                "Permanent Material attachment failure for notification '%s' ('%s') — marking FAILED, no retry",
                notificationId, failureMessage);
        final LoggingEventBuilder loggingEventBuilder = logger.atWarn()
                .addKeyValue(DEPENDENCY, "material")
                .addKeyValue(OPERATION, "download")
                .addKeyValue(CHANNEL, "email")
                .addKeyValue(OUTCOME, "permanent-failure");
        statusCode.ifPresent(code -> loggingEventBuilder.addKeyValue(STATUS_CODE, code));
        loggingEventBuilder.log(message);
    }

    /** {@code failureReason} says why when Gov.Notify gave no HTTP response, so the outage alert can count it. */
    private void logGovNotifySendFailedPermanently(
            final UUID notificationId, final Optional<Integer> statusCode, final Optional<FailureReason> failureReason) {
        final String message = format(
                "Permanent send failure for notification '%s' (http '%s') — marking FAILED",
                notificationId, statusCodeText(statusCode));
        final LoggingEventBuilder loggingEventBuilder = logger.atWarn()
                .addKeyValue(DEPENDENCY, "gov-uk-notify")
                .addKeyValue(OPERATION, "send")
                .addKeyValue(CHANNEL, "email")
                .addKeyValue(OUTCOME, "permanent-failure");
        statusCode.ifPresent(code -> loggingEventBuilder.addKeyValue(STATUS_CODE, code));
        failureReason.ifPresent(reason -> loggingEventBuilder.addKeyValue(REASON, reason.getFieldValue()));
        loggingEventBuilder.log(message);
    }

    private void logOffice365SendFailedPermanently(final UUID notificationId, final Optional<Integer> statusCode) {
        final String message = format(
                "Permanent Office 365 send failure for notification '%s' (http '%s') — marking FAILED",
                notificationId, statusCodeText(statusCode));
        final LoggingEventBuilder loggingEventBuilder = logger.atWarn()
                .addKeyValue(DEPENDENCY, "microsoft-365")
                .addKeyValue(OPERATION, "send")
                .addKeyValue(CHANNEL, "email")
                .addKeyValue(OUTCOME, "permanent-failure");
        statusCode.ifPresent(code -> loggingEventBuilder.addKeyValue(STATUS_CODE, code));
        loggingEventBuilder.log(message);
    }

    private String statusCodeText(final Optional<Integer> statusCode) {
        final Optional<String> statusCodeText = statusCode.map(String::valueOf);
        return statusCodeText.orElse("none");
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return of(retryDurationsSecs);
    }
}
