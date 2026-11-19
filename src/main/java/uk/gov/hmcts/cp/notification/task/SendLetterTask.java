package uk.gov.hmcts.cp.notification.task;

import static java.util.List.copyOf;
import static java.util.Optional.of;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.material.MaterialAttachmentDownloader;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.sender.Attachment;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.LetterSender;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Downloads the letter's PDF through Material and sends it to Gov.Notify as a precompiled letter (legacy
 * {@code send-letter}). On success the letter's status is polled by {@link CheckLetterStatusTask}. A failure of either
 * call is handed to the handler for the dependency that failed, which decides whether to retry it or record the
 * notification as FAILED.
 */
@Task(SendLetterTask.TASK_NAME)
@Component
public class SendLetterTask implements ExecutableTask {
    public static final String TASK_NAME = "SEND_LETTER";

    private final MaterialAttachmentDownloader materialAttachmentDownloader;
    private final LetterSender letterSender;
    private final TaskRetrySchedule taskRetrySchedule;
    private final ExecutionService executionService;
    private final CpTaskFactory taskFactory;
    private final AttachmentDownloadFailureHandler attachmentDownloadFailureHandler;
    private final GovNotifyFailureHandler govNotifyFailureHandler;
    private final List<Long> retryDurationsSecs;

    public SendLetterTask(
            final MaterialAttachmentDownloader materialAttachmentDownloader,
            final LetterSender letterSender,
            final TaskRetrySchedule taskRetrySchedule,
            final ExecutionService executionService,
            final CpTaskFactory taskFactory,
            final AttachmentDownloadFailureHandler attachmentDownloadFailureHandler,
            final GovNotifyFailureHandler govNotifyFailureHandler,
            @Value("${cp.notification.retry.letter-durations-secs}") final List<Long> retryDurationsSecs) {
        this.materialAttachmentDownloader = materialAttachmentDownloader;
        this.letterSender = letterSender;
        this.taskRetrySchedule = taskRetrySchedule;
        this.executionService = executionService;
        this.taskFactory = taskFactory;
        this.attachmentDownloadFailureHandler = attachmentDownloadFailureHandler;
        this.govNotifyFailureHandler = govNotifyFailureHandler;
        this.retryDurationsSecs = copyOf(retryDurationsSecs);
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final LetterJob letter = LetterJob.from(executionInfo.getJobData());
        final UUID notificationId = letter.notificationId();
        ExecutionInfo result;
        try {
            final Attachment letterDocument =
                    materialAttachmentDownloader.download(letter.letterUrl(), notificationId, LETTER);
            final String reference =
                    letterSender.sendLetter(notificationId, letterDocument.content(), letter.firstClass());
            executionService.executeWith(taskFactory.createCheckLetterStatusJob(letter, reference));
            result = taskRetrySchedule.completed(executionInfo);
        } catch (final MaterialAttachmentException materialAttachmentException) {
            result = attachmentDownloadFailureHandler.materialDownloadFailed(
                    notificationId, LETTER, materialAttachmentException, executionInfo, retryDurationsSecs);
        } catch (final GovNotifyException govNotifyException) {
            result = govNotifyFailureHandler.sendFailed(
                    notificationId, LETTER, govNotifyException, executionInfo, retryDurationsSecs);
        }
        return result;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return of(retryDurationsSecs);
    }
}
