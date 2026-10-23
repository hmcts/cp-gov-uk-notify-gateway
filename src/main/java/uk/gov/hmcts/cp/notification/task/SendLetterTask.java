package uk.gov.hmcts.cp.notification.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
    private final List<Long> retryDurationsSecs;

    public SendLetterTask(
            final MaterialAttachmentDownloader materialAttachmentDownloader,
            final GovNotifyClient govNotifyClient,
            final TransientFailurePolicy failurePolicy,
            final ExecutionService executionService,
            final CpTaskFactory taskFactory,
            @Value("${cp.notification.retry.letter-durations-secs}") final List<Long> retryDurationsSecs) {
        this.materialAttachmentDownloader = materialAttachmentDownloader;
        this.govNotifyClient = govNotifyClient;
        this.failurePolicy = failurePolicy;
        this.executionService = executionService;
        this.taskFactory = taskFactory;
        this.retryDurationsSecs = List.copyOf(retryDurationsSecs);
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final LetterJob letter = LetterJob.from(executionInfo.getJobData());
        ExecutionInfo result;
        try {
            final byte[] pdf = materialAttachmentDownloader.download(letter.letterUrl(), letter.notificationId()).content();
            result = send(letter, pdf, executionInfo);
        } catch (final MaterialAttachmentException e) {
            result = downloadFailed(letter, e, executionInfo);
        }
        return result;
    }

    private ExecutionInfo send(final LetterJob letter, final byte[] pdf, final ExecutionInfo executionInfo) {
        ExecutionInfo result;
        try {
            LOG.info("Sending letter for notification {} (first class: {})", letter.notificationId(), letter.firstClass());
            final String reference = govNotifyClient.sendPrecompiledLetter(letter.notificationId(), pdf, letter.firstClass());
            executionService.executeWith(taskFactory.createCheckLetterStatusJob(letter, reference));
            result = TransientFailurePolicy.completed(executionInfo);
        } catch (final GovNotifyException e) {
            if (GovNotifyFailureClassifier.isTemporary(e.getHttpStatus(), e.getMessage())) {
                result = failurePolicy.retryOrFail(letter.notificationId(), e.getHttpStatus(),
                        "Gov.Notify letter send did not recover within the retry window", executionInfo,
                        retryDurationsSecs);
            } else {
                LOG.warn("Permanent letter send failure for notification {} (http {}) — marking FAILED",
                        letter.notificationId(), e.getHttpStatus());
                result = failurePolicy.fail(letter.notificationId(), e.getHttpStatus(), e.getMessage(), executionInfo);
            }
        }
        return result;
    }

    private ExecutionInfo downloadFailed(
            final LetterJob letter, final MaterialAttachmentException failure, final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        if (failure.isRetryable()) {
            result = failurePolicy.retryOrFail(letter.notificationId(), failure.getStatusCode(),
                    "Material letter download did not recover within the retry window", executionInfo,
                    retryDurationsSecs);
        } else {
            LOG.warn("Permanent Material letter download failure for notification {} ({}) — marking FAILED, no retry",
                    letter.notificationId(), failure.getMessage());
            result = failurePolicy.fail(letter.notificationId(), failure.getStatusCode(), failure.getMessage(),
                    executionInfo);
        }
        return result;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(retryDurationsSecs);
    }
}
