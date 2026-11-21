package uk.gov.hmcts.cp.notification.task;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.DELIVERY_FAILED;
import static uk.gov.hmcts.cp.notification.sender.NotificationStatus.ACCEPTED;
import static uk.gov.hmcts.cp.notification.sender.NotificationStatus.CREATED;
import static uk.gov.hmcts.cp.notification.sender.NotificationStatus.INVALID_REQUEST;
import static uk.gov.hmcts.cp.notification.sender.NotificationStatus.PENDING_VIRUS_CHECK;
import static uk.gov.hmcts.cp.notification.sender.NotificationStatus.RECEIVED;
import static uk.gov.hmcts.cp.notification.sender.NotificationStatus.SENDING;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.sender.GovNotifyClient;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.NotificationStatus;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Polls Gov.Notify for a letter's status, for {@link CheckLetterStatusTask} and
 * {@link CheckAcceptedLetterStatusTask}, which differ only in what {@code accepted} means and how long they
 * keep polling. A letter is sent once Gov.Notify has {@code received} it (letters never reach
 * {@code delivered}). Gov.Notify failing the letter's validation sends it again, up to
 * {@link LetterJob#RESEND_ATTEMPTS} times, as legacy did. Other statuses follow the email rule (ADR-002 D2):
 * a status known to be in progress is polled again, anything else fails the letter at once.
 */
@Component
public class LetterStatusPoller {
    private static final Logger LOG = getLogger(LetterStatusPoller.class);

    private static final int BAD_REQUEST = 400;
    private static final Set<NotificationStatus> IN_PROGRESS =
            EnumSet.of(CREATED, SENDING, PENDING_VIRUS_CHECK);

    private final GovNotifyClient govNotifyClient;
    private final NotificationStatusService statusService;
    private final TransientFailurePolicy failurePolicy;
    private final FailedNotificationRecorder failedNotificationRecorder;
    private final GovNotifyFailureHandler govNotifyFailureHandler;
    private final TaskRetrySchedule taskRetrySchedule;
    private final ExecutionService executionService;
    private final CpTaskFactory taskFactory;

    public LetterStatusPoller(
            final GovNotifyClient govNotifyClient,
            final NotificationStatusService statusService,
            final TransientFailurePolicy failurePolicy,
            final FailedNotificationRecorder failedNotificationRecorder,
            final GovNotifyFailureHandler govNotifyFailureHandler,
            final TaskRetrySchedule taskRetrySchedule,
            final ExecutionService executionService,
            final CpTaskFactory taskFactory) {
        this.govNotifyClient = govNotifyClient;
        this.statusService = statusService;
        this.failurePolicy = failurePolicy;
        this.failedNotificationRecorder = failedNotificationRecorder;
        this.govNotifyFailureHandler = govNotifyFailureHandler;
        this.taskRetrySchedule = taskRetrySchedule;
        this.executionService = executionService;
        this.taskFactory = taskFactory;
    }

    /** What a status task does when Gov.Notify has accepted the letter but not yet received it. */
    @FunctionalInterface
    public interface WhenAccepted {
        ExecutionInfo handle(LetterJob letter, String reference, ExecutionInfo executionInfo);
    }

    public ExecutionInfo poll(
            final ExecutionInfo executionInfo, final List<Long> retryDurationsSecs, final WhenAccepted whenAccepted) {
        final LetterJob letter = LetterJob.from(executionInfo.getJobData());
        final String reference = executionInfo.getJobData().getString(CheckLetterStatusTask.KEY_REFERENCE);
        ExecutionInfo result;
        try {
            final NotificationStatus status = govNotifyClient.checkStatus(reference);
            if (status == ACCEPTED) {
                result = whenAccepted.handle(letter, reference, executionInfo);
            } else {
                result = evaluate(status, letter, executionInfo, retryDurationsSecs);
            }
        } catch (final GovNotifyException govNotifyException) {
            result = govNotifyFailureHandler.statusCheckFailed(
                    letter.notificationId(), LETTER, govNotifyException, executionInfo, retryDurationsSecs);
        }
        return result;
    }

    /** Polls again later, failing the letter once this task's retries run out. */
    public ExecutionInfo pollAgain(
            final LetterJob letter, final NotificationStatus lastStatus, final ExecutionInfo executionInfo,
            final List<Long> retryDurationsSecs) {
        final String exhaustedReason = format(
                "Gov.Notify did not reach a terminal status within the retry window (last status '%s')",
                lastStatus.getStatus());
        return failurePolicy.pollAgainOrFail(
                letter.notificationId(), LETTER, exhaustedReason, executionInfo, retryDurationsSecs);
    }

    private ExecutionInfo evaluate(
            final NotificationStatus status, final LetterJob letter, final ExecutionInfo executionInfo,
            final List<Long> retryDurationsSecs) {
        final ExecutionInfo result;
        if (status == RECEIVED) {
            statusService.markLetterSent(letter.notificationId());
            result = taskRetrySchedule.completed(executionInfo);
        } else if (status == INVALID_REQUEST) {
            result = validationFailed(letter, executionInfo);
        } else if (IN_PROGRESS.contains(status)) {
            result = pollAgain(letter, status, executionInfo, retryDurationsSecs);
        } else {
            final String failedReason = format("Gov.Notify responded with status '%s'", status.getStatus());
            final NotificationFailure notificationFailure =
                    new NotificationFailure(letter.notificationId(), LETTER, null, failedReason, DELIVERY_FAILED);
            result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
        }
        return result;
    }

    // Legacy parity (Notification.markAsInvalid): the message gives the resends left before this one; the
    // letter is sent again, from a fresh download, until none are left, and then fails with 400.
    private ExecutionInfo validationFailed(final LetterJob letter, final ExecutionInfo executionInfo) {
        final String reason = "Validation failed for '" + letter.notificationId()
                + "' automatic resend attempt, remaining = " + letter.resendAttemptsRemaining();
        final ExecutionInfo result;
        if (letter.canResend()) {
            LOG.warn("Letter notification {} failed Gov.Notify validation — sending it again ({})",
                    letter.notificationId(), reason);
            statusService.markRequeued(letter.notificationId());
            executionService.executeWith(taskFactory.createSendLetterJob(letter.forResend()));
            result = taskRetrySchedule.completed(executionInfo);
        } else {
            final NotificationFailure notificationFailure =
                    new NotificationFailure(letter.notificationId(), LETTER, BAD_REQUEST, reason, DELIVERY_FAILED);
            result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
        }
        return result;
    }

}
