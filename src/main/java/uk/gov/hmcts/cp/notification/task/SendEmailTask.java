package uk.gov.hmcts.cp.notification.task;

import com.azure.storage.blob.models.BlobStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import uk.gov.hmcts.cp.notification.blob.AttachmentDownloader;
import uk.gov.hmcts.cp.notification.blob.PermanentBlobException;
import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.sender.EmailSender;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.GovNotifyFailureClassifier;
import uk.gov.hmcts.cp.notification.sender.Office365NotYetSupportedException;
import uk.gov.hmcts.cp.notification.sender.SendResult;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;

@Task(SendEmailTask.TASK_NAME)
@Component
public class SendEmailTask implements ExecutableTask {
    public static final String TASK_NAME = "SEND_EMAIL";

    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;

    private static final Logger LOG = LoggerFactory.getLogger(SendEmailTask.class);

    private final AttachmentDownloader attachmentDownloader;
    private final EmailSender emailSender;
    private final TransientFailurePolicy failurePolicy;
    private final ExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final CpTaskFactory taskFactory;
    private final List<Long> retryDurationsSecs;

    public SendEmailTask(
            final AttachmentDownloader attachmentDownloader,
            final EmailSender emailSender,
            final TransientFailurePolicy failurePolicy,
            final ExecutionService executionService,
            final ObjectMapper objectMapper,
            final CpTaskFactory taskFactory,
            @Value("${cp.notification.retry.email-durations-secs}") final List<Long> retryDurationsSecs) {
        this.attachmentDownloader = attachmentDownloader;
        this.emailSender = emailSender;
        this.failurePolicy = failurePolicy;
        this.executionService = executionService;
        this.objectMapper = objectMapper;
        this.taskFactory = taskFactory;
        this.retryDurationsSecs = retryDurationsSecs;
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final SendEmailCommand command = objectMapper.readValue(
                executionInfo.getJobData().toString(), SendEmailCommand.class);
        ExecutionInfo result;
        try {
            result = send(command, downloadAttachment(command), executionInfo);
        } catch (final PermanentBlobException e) {
            LOG.warn("Permanent attachment failure for notification {} — marking FAILED, no retry",
                    command.notificationId());
            result = failurePolicy.fail(command.notificationId(), e.getStatusCode(), e.getMessage(), executionInfo);
        } catch (final BlobStorageException e) {
            result = failurePolicy.retryOrFail(command.notificationId(), e.getStatusCode(),
                    "Attachment download did not recover within the retry window", executionInfo);
        }
        return result;
    }

    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private byte[] downloadAttachment(final SendEmailCommand command) {
        byte[] attachment = null;
        if (StringUtils.hasText(command.fileUri())) {
            attachment = attachmentDownloader.download(command.fileUri());
        }
        return attachment;
    }

    private ExecutionInfo send(
            final SendEmailCommand command, final byte[] attachment, final ExecutionInfo executionInfo) {
        ExecutionInfo result;
        try {
            final SendResult sendResult = emailSender.sendEmail(command, attachment);
            executionService.executeWith(taskFactory.createCheckStatusJob(command, sendResult));
            result = TransientFailurePolicy.completed(executionInfo);
        } catch (final Office365NotYetSupportedException e) {
            LOG.warn("Notification {} requires the Office 365 route (attachment > 2MB) which is not yet "
                    + "available (NG-S10) — marking FAILED", command.notificationId());
            result = failurePolicy.fail(command.notificationId(), HTTP_PAYLOAD_TOO_LARGE, e.getMessage(), executionInfo);
        } catch (final GovNotifyException e) {
            if (GovNotifyFailureClassifier.isTemporary(e.getHttpStatus(), e.getMessage())) {
                result = failurePolicy.retryOrFail(command.notificationId(), e.getHttpStatus(),
                        "Gov.Notify send did not recover within the retry window", executionInfo);
            } else {
                LOG.warn("Permanent send failure for notification {} (http {}) — marking FAILED",
                        command.notificationId(), e.getHttpStatus());
                result = failurePolicy.fail(command.notificationId(), e.getHttpStatus(), e.getMessage(), executionInfo);
            }
        }
        return result;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(retryDurationsSecs);
    }
}
