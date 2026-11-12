package uk.gov.hmcts.cp.notification.task;

import static java.lang.String.format;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.PERMANENT_FAILURE;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.SUCCEEDED;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.Dependency.GOV_UK_NOTIFY;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.logging.DependencyCall;
import uk.gov.hmcts.cp.notification.logging.DependencyCallLogger;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentDownloader;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.sender.GovNotifyClient;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.GovNotifyFailureClassifier;
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

    private static final Logger LOG = LoggerFactory.getLogger(SendLetterTask.class);

    private final MaterialAttachmentDownloader materialAttachmentDownloader;
    private final GovNotifyClient govNotifyClient;
    private final TransientFailurePolicy failurePolicy;
    private final ExecutionService executionService;
    private final CpTaskFactory taskFactory;
    private final DependencyCallLogger dependencyCallLogger;
    private final List<Long> retryDurationsSecs;

    public SendLetterTask(
            final MaterialAttachmentDownloader materialAttachmentDownloader,
            final GovNotifyClient govNotifyClient,
            final TransientFailurePolicy failurePolicy,
            final ExecutionService executionService,
            final CpTaskFactory taskFactory,
            final DependencyCallLogger dependencyCallLogger,
            @Value("${cp.notification.retry.letter-durations-secs}") final List<Long> retryDurationsSecs) {
        this.materialAttachmentDownloader = materialAttachmentDownloader;
        this.govNotifyClient = govNotifyClient;
        this.failurePolicy = failurePolicy;
        this.executionService = executionService;
        this.taskFactory = taskFactory;
        this.dependencyCallLogger = dependencyCallLogger;
        this.retryDurationsSecs = List.copyOf(retryDurationsSecs);
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
            LOG.info("Sending letter for notification {} (first class: {})", letter.notificationId(), letter.firstClass());
            final String reference = govNotifyClient.sendPrecompiledLetter(letter.notificationId(), pdf, letter.firstClass());
            final DependencyCall dependencyCall =
                    new DependencyCall(GOV_UK_NOTIFY, SEND, LETTER, SUCCEEDED, empty());
            final UUID notificationId = letter.notificationId();
            final String message = format(
                    "Sent letter for notification '%s' via Gov.Notify (reference '%s')", notificationId, reference);
            dependencyCallLogger.info(dependencyCall, message);
            executionService.executeWith(taskFactory.createCheckLetterStatusJob(letter, reference));
            result = TransientFailurePolicy.completed(executionInfo);
        } catch (final GovNotifyException govNotifyException) {
            if (GovNotifyFailureClassifier.isTemporary(
                    govNotifyException.getHttpStatus(), govNotifyException.getMessage())) {
                result = failurePolicy.retryOrFail(letter.notificationId(), govNotifyException.getHttpStatus(),
                        "Gov.Notify letter send did not recover within the retry window", executionInfo,
                        retryDurationsSecs, RetryCause.GOV_NOTIFY_SEND, LETTER);
            } else {
                final Integer statusCode = govNotifyException.getHttpStatus();
                final DependencyCall dependencyCall = new DependencyCall(
                        GOV_UK_NOTIFY, SEND, LETTER, PERMANENT_FAILURE, ofNullable(statusCode));
                final UUID notificationId = letter.notificationId();
                final String message = format(
                        "Permanent letter send failure for notification '%s' (http '%s') — marking FAILED",
                        notificationId, statusCode);
                dependencyCallLogger.warn(dependencyCall, message);
                result = failurePolicy.fail(letter.notificationId(), govNotifyException.getHttpStatus(),
                        govNotifyException.getMessage(), executionInfo);
            }
        }
        return result;
    }

    private ExecutionInfo downloadFailed(
            final LetterJob letter,
            final MaterialAttachmentException materialAttachmentException,
            final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        if (materialAttachmentException.isRetryable()) {
            result = failurePolicy.retryOrFail(letter.notificationId(), materialAttachmentException.getStatusCode(),
                    "Material letter download did not recover within the retry window", executionInfo,
                    retryDurationsSecs, RetryCause.MATERIAL_DOWNLOAD, LETTER);
        } else {
            final Integer statusCode = materialAttachmentException.getStatusCode();
            final DependencyCall dependencyCall = new DependencyCall(
                    MATERIAL, DOWNLOAD, LETTER, PERMANENT_FAILURE, ofNullable(statusCode));
            final UUID notificationId = letter.notificationId();
            final String failureMessage = materialAttachmentException.getMessage();
            final String message = format(
                    "Permanent Material letter download failure for notification '%s' ('%s') — marking FAILED, no retry",
                    notificationId, failureMessage);
            dependencyCallLogger.warn(dependencyCall, message);
            result = failurePolicy.fail(letter.notificationId(), materialAttachmentException.getStatusCode(),
                    materialAttachmentException.getMessage(), executionInfo);
        }
        return result;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(retryDurationsSecs);
    }
}
