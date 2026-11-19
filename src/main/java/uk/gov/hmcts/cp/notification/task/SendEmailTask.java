package uk.gov.hmcts.cp.notification.task;

import static java.util.List.copyOf;
import static java.util.Optional.of;
import static org.springframework.util.StringUtils.hasText;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import uk.gov.hmcts.cp.notification.blob.AttachmentDownloader;
import uk.gov.hmcts.cp.notification.blob.PermanentBlobException;
import uk.gov.hmcts.cp.notification.blob.TransientBlobException;
import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentDownloader;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.sender.Attachment;
import uk.gov.hmcts.cp.notification.sender.EmailSender;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.Office365Exception;
import uk.gov.hmcts.cp.notification.sender.SendResult;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Downloads the email's attachment, if it has one, and sends the email. A failure of either call is handed to the
 * handler for the dependency that failed, which decides whether to retry it or record the notification as FAILED.
 */
@Task(SendEmailTask.TASK_NAME)
@Component
public class SendEmailTask implements ExecutableTask {
    public static final String TASK_NAME = "SEND_EMAIL";

    private final AttachmentDownloader attachmentDownloader;
    private final MaterialAttachmentDownloader materialAttachmentDownloader;
    private final EmailSender emailSender;
    private final TaskRetrySchedule taskRetrySchedule;
    private final ExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final CpTaskFactory taskFactory;
    private final AttachmentDownloadFailureHandler attachmentDownloadFailureHandler;
    private final GovNotifyFailureHandler govNotifyFailureHandler;
    private final Office365SendFailureHandler office365SendFailureHandler;
    private final List<Long> retryDurationsSecs;

    public SendEmailTask(
            final AttachmentDownloader attachmentDownloader,
            final MaterialAttachmentDownloader materialAttachmentDownloader,
            final EmailSender emailSender,
            final TaskRetrySchedule taskRetrySchedule,
            final ExecutionService executionService,
            final ObjectMapper objectMapper,
            final CpTaskFactory taskFactory,
            final AttachmentDownloadFailureHandler attachmentDownloadFailureHandler,
            final GovNotifyFailureHandler govNotifyFailureHandler,
            final Office365SendFailureHandler office365SendFailureHandler,
            @Value("${cp.notification.retry.email-durations-secs}") final List<Long> retryDurationsSecs) {
        this.attachmentDownloader = attachmentDownloader;
        this.materialAttachmentDownloader = materialAttachmentDownloader;
        this.emailSender = emailSender;
        this.taskRetrySchedule = taskRetrySchedule;
        this.executionService = executionService;
        this.objectMapper = objectMapper;
        this.taskFactory = taskFactory;
        this.attachmentDownloadFailureHandler = attachmentDownloadFailureHandler;
        this.govNotifyFailureHandler = govNotifyFailureHandler;
        this.office365SendFailureHandler = office365SendFailureHandler;
        this.retryDurationsSecs = copyOf(retryDurationsSecs);
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final SendEmailCommand command = objectMapper.readValue(
                executionInfo.getJobData().toString(), SendEmailCommand.class);
        final UUID notificationId = command.notificationId();
        ExecutionInfo result;
        try {
            final Attachment attachment = downloadAttachment(command);
            final SendResult sendResult = emailSender.sendEmail(command, attachment);
            // Office 365 has already sent the email, so SENT is recorded by its own task (legacy: mark-as-sent
            // command, no poll); Gov.Notify is polled. Either job commits with this task's completion.
            executionService.executeWith(sendResult.sentOnSubmit()
                    ? taskFactory.createRecordSentJob(command, sendResult)
                    : taskFactory.createCheckStatusJob(command, sendResult));
            result = taskRetrySchedule.completed(executionInfo);
        } catch (final PermanentBlobException permanentBlobException) {
            result = attachmentDownloadFailureHandler.blobDownloadFailed(
                    notificationId, permanentBlobException, executionInfo);
        } catch (final TransientBlobException transientBlobException) {
            result = attachmentDownloadFailureHandler.blobDownloadFailed(
                    notificationId, transientBlobException, executionInfo, retryDurationsSecs);
        } catch (final MaterialAttachmentException materialAttachmentException) {
            result = attachmentDownloadFailureHandler.materialDownloadFailed(
                    notificationId, EMAIL, materialAttachmentException, executionInfo, retryDurationsSecs);
        } catch (final Office365Exception office365Exception) {
            result = office365SendFailureHandler.sendFailed(
                    notificationId, office365Exception, executionInfo, retryDurationsSecs);
        } catch (final GovNotifyException govNotifyException) {
            result = govNotifyFailureHandler.sendFailed(
                    notificationId, EMAIL, govNotifyException, executionInfo, retryDurationsSecs);
        }
        return result;
    }

    // Precedence (legacy parity): a non-blank materialUrl wins over fileUri; a blank one counts as absent.
    private Attachment downloadAttachment(final SendEmailCommand command) {
        Attachment attachment = null;
        if (hasText(command.materialUrl())) {
            attachment = materialAttachmentDownloader.download(command.materialUrl(), command.notificationId(), EMAIL);
        } else if (hasText(command.fileUri())) {
            attachment = Attachment.withoutFilename(attachmentDownloader.download(command.fileUri()));
        }
        return attachment;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return of(retryDurationsSecs);
    }
}
