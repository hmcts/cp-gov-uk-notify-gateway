package uk.gov.hmcts.cp.notification.task;

import jakarta.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.service.NotificationEmailDetails;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Records SENT for an email a route has already delivered (Office 365), as its own task so a failure here is
 * retried without sending the email again — legacy hands the same step to a {@code mark-as-sent} command on
 * Artemis. It never marks FAILED: the email was sent. When the last retry fails the job is left in the
 * {@code jobs} table with no retries remaining, which cp-task-manager never picks up again, so it can be
 * inspected and replayed by hand (the equivalent of legacy's DLQ; ADR-002 D13).
 */
@Task(RecordSentTask.TASK_NAME)
@Component
public class RecordSentTask implements ExecutableTask {
    public static final String TASK_NAME = "RECORD_SENT";
    public static final String KEY_NOTIFICATION_ID = "notificationId";
    public static final String KEY_EMAIL_SUBJECT = "emailSubject";
    public static final String KEY_EMAIL_BODY = "emailBody";
    public static final String KEY_REPLY_TO_ADDRESS = "replyToAddress";

    private static final Logger LOG = LoggerFactory.getLogger(RecordSentTask.class);

    private final NotificationStatusService statusService;
    private final List<Long> retryDurationsSecs;

    public RecordSentTask(
            final NotificationStatusService statusService,
            @Value("${cp.notification.retry.email-durations-secs}") final List<Long> retryDurationsSecs) {
        this.statusService = statusService;
        this.retryDurationsSecs = retryDurationsSecs;
    }

    // markSent runs in its own transaction: joined to the task's, its failure would roll the task back and
    // cp-task-manager would release the job without using up a retry, running it again at once, forever.
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // any failure (DB, Service Bus) is retried, never resent
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final JsonObject jobData = executionInfo.getJobData();
        final UUID notificationId = UUID.fromString(jobData.getString(KEY_NOTIFICATION_ID));
        ExecutionInfo result;
        try {
            statusService.markSentInNewTransaction(notificationId, new NotificationEmailDetails(
                    jobData.getString(KEY_EMAIL_SUBJECT, null),
                    jobData.getString(KEY_EMAIL_BODY, null),
                    jobData.getString(KEY_REPLY_TO_ADDRESS, null)));
            result = TransientFailurePolicy.completed(executionInfo);
        } catch (final RuntimeException e) {
            if (isLastAttempt(executionInfo)) {
                LOG.error("Notification {} was sent via Office 365 but SENT could not be recorded after every retry "
                        + "({}) — leaving its {} job parked for manual replay", notificationId,
                        e.getClass().getSimpleName(), TASK_NAME);
            } else {
                LOG.warn("Notification {} was sent via Office 365 but SENT could not be recorded ({}) — will retry",
                        notificationId, e.getClass().getSimpleName());
            }
            result = TransientFailurePolicy.retry(executionInfo);
        }
        return result;
    }

    private static boolean isLastAttempt(final ExecutionInfo executionInfo) {
        final Integer remaining = executionInfo.getRetryAttemptsRemaining();
        return remaining == null || remaining <= 1;
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(retryDurationsSecs);
    }
}
