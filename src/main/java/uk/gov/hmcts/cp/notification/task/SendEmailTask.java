package uk.gov.hmcts.cp.notification.task;

import static java.util.Optional.ofNullable;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.PERMANENT_FAILURE;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Dependency.BLOB_STORAGE;
import static uk.gov.hmcts.cp.notification.logging.Dependency.GOV_UK_NOTIFY;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MICROSOFT_365;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;

import com.azure.storage.blob.models.BlobStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import uk.gov.hmcts.cp.notification.blob.AttachmentDownloader;
import uk.gov.hmcts.cp.notification.blob.PermanentBlobException;
import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.logging.DependencyCall;
import uk.gov.hmcts.cp.notification.logging.DependencyCallLogger;
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

    private final AttachmentDownloader attachmentDownloader;
    private final MaterialAttachmentDownloader materialAttachmentDownloader;
    private final EmailSender emailSender;
    private final TransientFailurePolicy failurePolicy;
    private final ExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final CpTaskFactory taskFactory;
    private final DependencyCallLogger dependencyCallLogger;
    private final List<Long> retryDurationsSecs;

    public SendEmailTask(
            final AttachmentDownloader attachmentDownloader,
            final MaterialAttachmentDownloader materialAttachmentDownloader,
            final EmailSender emailSender,
            final TransientFailurePolicy failurePolicy,
            final ExecutionService executionService,
            final ObjectMapper objectMapper,
            final CpTaskFactory taskFactory,
            final DependencyCallLogger dependencyCallLogger,
            @Value("${cp.notification.retry.email-durations-secs}") final List<Long> retryDurationsSecs) {
        this.attachmentDownloader = attachmentDownloader;
        this.materialAttachmentDownloader = materialAttachmentDownloader;
        this.emailSender = emailSender;
        this.failurePolicy = failurePolicy;
        this.executionService = executionService;
        this.objectMapper = objectMapper;
        this.taskFactory = taskFactory;
        this.dependencyCallLogger = dependencyCallLogger;
        this.retryDurationsSecs = retryDurationsSecs;
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final SendEmailCommand command = objectMapper.readValue(
                executionInfo.getJobData().toString(), SendEmailCommand.class);
        ExecutionInfo result;
        try {
            result = send(command, downloadAttachment(command), executionInfo);
        } catch (final PermanentBlobException permanentBlobException) {
            final Integer statusCode = permanentBlobException.getStatusCode();
            final DependencyCall dependencyCall = new DependencyCall(
                    BLOB_STORAGE, DOWNLOAD, EMAIL, PERMANENT_FAILURE, ofNullable(statusCode));
            dependencyCallLogger.warn(dependencyCall,
                    "Permanent attachment failure for notification {} — marking FAILED, no retry",
                    command.notificationId());
            result = failurePolicy.fail(command.notificationId(), permanentBlobException.getStatusCode(),
                    permanentBlobException.getMessage(), executionInfo);
        } catch (final BlobStorageException blobStorageException) {
            result = failurePolicy.retryOrFail(command.notificationId(), blobStorageException.getStatusCode(),
                    "Attachment download did not recover within the retry window", executionInfo,
                    RetryCause.ATTACHMENT_DOWNLOAD, EMAIL);
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
        if (materialAttachmentException.isRetryable()) {
            result = failurePolicy.retryOrFail(command.notificationId(), materialAttachmentException.getStatusCode(),
                    "Material attachment download did not recover within the retry window", executionInfo,
                    RetryCause.MATERIAL_DOWNLOAD, EMAIL);
        } else {
            final Integer statusCode = materialAttachmentException.getStatusCode();
            final DependencyCall dependencyCall = new DependencyCall(
                    MATERIAL, DOWNLOAD, EMAIL, PERMANENT_FAILURE, ofNullable(statusCode));
            dependencyCallLogger.warn(dependencyCall,
                    "Permanent Material attachment failure for notification {} ({}) — marking FAILED, no retry",
                    command.notificationId(), materialAttachmentException.getMessage());
            result = failurePolicy.fail(command.notificationId(), materialAttachmentException.getStatusCode(),
                    materialAttachmentException.getMessage(), executionInfo);
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
        } catch (final Office365Exception office365Exception) {
            result = office365SendFailed(command, office365Exception, executionInfo);
        } catch (final GovNotifyException govNotifyException) {
            if (GovNotifyFailureClassifier.isTemporary(
                    govNotifyException.getHttpStatus(), govNotifyException.getMessage())) {
                result = failurePolicy.retryOrFail(command.notificationId(), govNotifyException.getHttpStatus(),
                        "Gov.Notify send did not recover within the retry window", executionInfo,
                        RetryCause.GOV_NOTIFY_SEND, EMAIL);
            } else {
                final Integer statusCode = govNotifyException.getHttpStatus();
                final DependencyCall dependencyCall = new DependencyCall(
                        GOV_UK_NOTIFY, SEND, EMAIL, PERMANENT_FAILURE, ofNullable(statusCode));
                dependencyCallLogger.warn(dependencyCall,
                        "Permanent send failure for notification {} (http {}) — marking FAILED",
                        command.notificationId(), govNotifyException.getHttpStatus());
                result = failurePolicy.fail(command.notificationId(), govNotifyException.getHttpStatus(),
                        govNotifyException.getMessage(), executionInfo);
            }
        }
        return result;
    }

    private ExecutionInfo office365SendFailed(
            final SendEmailCommand command, final Office365Exception office365Exception, final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        if (office365Exception.isPermanent()) {
            final Integer statusCode = office365Exception.getHttpStatus();
            final DependencyCall dependencyCall = new DependencyCall(
                    MICROSOFT_365, SEND, EMAIL, PERMANENT_FAILURE, ofNullable(statusCode));
            dependencyCallLogger.warn(dependencyCall,
                    "Permanent Office 365 send failure for notification {} (http {}) — marking FAILED",
                    command.notificationId(), office365Exception.getHttpStatus());
            result = failurePolicy.fail(command.notificationId(), office365Exception.getHttpStatus(),
                    office365Exception.getMessage(), executionInfo);
        } else {
            result = failurePolicy.retryOrFail(command.notificationId(), office365Exception.getHttpStatus(),
                    "Office 365 send did not recover within the retry window", executionInfo,
                    RetryCause.OFFICE365_SEND, EMAIL);
        }
        return result;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(retryDurationsSecs);
    }
}
