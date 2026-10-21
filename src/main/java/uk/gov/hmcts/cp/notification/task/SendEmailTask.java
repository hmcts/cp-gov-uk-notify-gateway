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
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentDownloader;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.sender.Attachment;
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

@Task(SendEmailTask.TASK_NAME)
@Component
public class SendEmailTask implements ExecutableTask {
    public static final String TASK_NAME = "SEND_EMAIL";

    private static final Logger LOG = LoggerFactory.getLogger(SendEmailTask.class);

    private final AttachmentDownloader attachmentDownloader;
    private final MaterialAttachmentDownloader materialAttachmentDownloader;
    private final EmailSender emailSender;
    private final TransientFailurePolicy failurePolicy;
    private final ExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final CpTaskFactory taskFactory;
    private final List<Long> retryDurationsSecs;

    public SendEmailTask(
            final AttachmentDownloader attachmentDownloader,
            final MaterialAttachmentDownloader materialAttachmentDownloader,
            final EmailSender emailSender,
            final TransientFailurePolicy failurePolicy,
            final ExecutionService executionService,
            final ObjectMapper objectMapper,
            final CpTaskFactory taskFactory,
            @Value("${cp.notification.retry.email-durations-secs}") final List<Long> retryDurationsSecs) {
        this.attachmentDownloader = attachmentDownloader;
        this.materialAttachmentDownloader = materialAttachmentDownloader;
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
        } catch (final MaterialAttachmentException e) {
            result = materialAttachmentFailed(command, e, executionInfo);
        }
        return result;
    }

    private ExecutionInfo materialAttachmentFailed(
            final SendEmailCommand command, final MaterialAttachmentException failure, final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        if (failure.isRetryable()) {
            result = failurePolicy.retryOrFail(command.notificationId(), failure.getStatusCode(),
                    "Material attachment download did not recover within the retry window", executionInfo);
        } else {
            LOG.warn("Permanent Material attachment failure for notification {} ({}) — marking FAILED, no retry",
                    command.notificationId(), failure.getMessage());
            result = failurePolicy.fail(command.notificationId(), failure.getStatusCode(), failure.getMessage(),
                    executionInfo);
        }
        return result;
    }

    // Precedence (legacy parity): a non-blank materialUrl wins over fileUri; a blank one counts as absent.
    private Attachment downloadAttachment(final SendEmailCommand command) {
        Attachment attachment = null;
        if (StringUtils.hasText(command.materialUrl())) {
            attachment = materialAttachmentDownloader.download(command.materialUrl(), command.notificationId());
        } else if (StringUtils.hasText(command.fileUri())) {
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
            result = TransientFailurePolicy.completed(executionInfo);
        } catch (final Office365Exception e) {
            result = office365SendFailed(command, e, executionInfo);
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

    private ExecutionInfo office365SendFailed(
            final SendEmailCommand command, final Office365Exception failure, final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        if (failure.isPermanent()) {
            LOG.warn("Permanent Office 365 send failure for notification {} (http {}) — marking FAILED",
                    command.notificationId(), failure.getHttpStatus());
            result = failurePolicy.fail(command.notificationId(), failure.getHttpStatus(), failure.getMessage(),
                    executionInfo);
        } else {
            result = failurePolicy.retryOrFail(command.notificationId(), failure.getHttpStatus(),
                    "Office 365 send did not recover within the retry window", executionInfo);
        }
        return result;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(retryDurationsSecs);
    }
}
