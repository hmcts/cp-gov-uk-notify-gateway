package uk.gov.hmcts.cp.notification.task;

import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.INPROGRESS;

import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObject;
import jakarta.json.spi.JsonProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.util.List;
import java.util.UUID;

/**
 * Shared retry/failure scaffolding for notification tasks.
 *
 * <p>Every notification task classifies a failure as either <em>transient</em> (retry until the
 * task-manager retry window is exhausted, then record a FAILED status) or <em>permanent</em>
 * (record FAILED immediately, no retry). Centralising the decision here guarantees the two
 * behaviours are identical across tasks and — crucially — that an exhausted transient failure is
 * never dropped silently: it always terminates in {@link NotificationStatusService#markFailed}.
 *
 * <p>Without this, an unhandled exception escaping a task's {@code execute} is caught by the
 * task-manager's generic catch-all, retried, and then quietly released once retries run out —
 * leaving the notification stuck in its pre-send state with no FAILED status recorded.

 */
@Component
public class TransientFailurePolicy {
    private static final Logger LOG = LoggerFactory.getLogger(TransientFailurePolicy.class);

    public static final String KEY_FINAL_ATTEMPT = "finalAttempt";

    // Created once: Json.createObjectBuilder() looks up a new JSON provider on every call.
    private static final JsonBuilderFactory JSON_BUILDER_FACTORY = JsonProvider.provider().createBuilderFactory(null);

    private final NotificationStatusService statusService;
    private final Clock clock;
    private final List<Long> retryDurationsSecs;

    public TransientFailurePolicy(
            final NotificationStatusService statusService,
            final Clock clock,
            @Value("${cp.notification.retry.email-durations-secs}") final List<Long> retryDurationsSecs) {
        this.statusService = statusService;
        this.clock = clock;
        this.retryDurationsSecs = List.copyOf(retryDurationsSecs);
    }

    /**
     * Handle a transient failure: retry while the task-manager retry window still has attempts
     * remaining, otherwise mark the notification FAILED with {@code exhaustedReason} and complete.
     *
     * @param notificationId  the notification being processed
     * @param statusCode      the HTTP/status code of the failure, or {@code null} if none applies
     * @param exhaustedReason the failure reason recorded once retries are exhausted
     * @param executionInfo   the current execution
     * @return {@code INPROGRESS} + retry while more than one attempt remains, {@code INPROGRESS} rescheduled
     *         as the final attempt when one remains, otherwise {@code COMPLETED}
     */
    public ExecutionInfo retryOrFail(
            final UUID notificationId,
            final Integer statusCode,
            final String exhaustedReason,
            final ExecutionInfo executionInfo) {
        return retryOrFail(notificationId, statusCode, exhaustedReason, executionInfo, retryDurationsSecs);
    }

    /**
     * {@link #retryOrFail(UUID, Integer, String, ExecutionInfo)} for a task with its own retry durations (the
     * letter tasks): the final attempt is scheduled after the last of {@code taskRetryDurationsSecs}.
     */
    public ExecutionInfo retryOrFail(
            final UUID notificationId,
            final Integer statusCode,
            final String exhaustedReason,
            final ExecutionInfo executionInfo,
            final List<Long> taskRetryDurationsSecs) {
        final ExecutionInfo result;
        if (retriesExhausted(executionInfo)) {
            LOG.warn("Notification {} did not resolve (statusCode {}) after exhausting retries — marking FAILED",
                    notificationId, statusCode);
            result = fail(notificationId, statusCode, exhaustedReason, executionInfo);
        } else if (isLastRetry(executionInfo)) {
            LOG.info("Transient failure for notification {} (statusCode {}) — scheduling the final attempt",
                    notificationId, statusCode);
            result = finalAttempt(executionInfo, taskRetryDurationsSecs.getLast());
        } else {
            LOG.info("Transient failure for notification {} (statusCode {}) — will retry", notificationId, statusCode);
            result = retry(executionInfo);
        }
        return result;
    }

    /**
     * Handle a permanent failure: record FAILED immediately and complete, with no retry.
     */
    public ExecutionInfo fail(
            final UUID notificationId,
            final Integer statusCode,
            final String reason,
            final ExecutionInfo executionInfo) {
        statusService.markFailed(notificationId, statusCode, reason);
        return completed(executionInfo);
    }

    public static ExecutionInfo completed(final ExecutionInfo executionInfo) {
        return executionInfo().from(executionInfo).withExecutionStatus(COMPLETED).build();
    }

    public static ExecutionInfo retry(final ExecutionInfo executionInfo) {
        return executionInfo().from(executionInfo)
                .withExecutionStatus(INPROGRESS)
                .withShouldRetry(true)
                .build();
    }

    // Workaround for cp-task-manager, which never picks up a job with no retry attempts remaining: the run
    // that would see retries exhausted never happens, so the notification stays QUEUED for good. Instead the
    // last retry reschedules the same task (INPROGRESS, no retry), which keeps its remaining count, and flags
    // it as the final attempt so a failure there marks it FAILED.
    private ExecutionInfo finalAttempt(final ExecutionInfo executionInfo, final long delaySecs) {
        final JsonObject jobData = JSON_BUILDER_FACTORY.createObjectBuilder(executionInfo.getJobData())
                .add(KEY_FINAL_ATTEMPT, true)
                .build();
        return executionInfo().from(executionInfo)
                .withJobData(jobData)
                .withExecutionStatus(INPROGRESS)
                .withShouldRetry(false)
                .withAssignedTaskStartTime(clock.zonedDateTime().plusSeconds(delaySecs))
                .build();
    }

    private static boolean retriesExhausted(final ExecutionInfo executionInfo) {
        final Integer remaining = executionInfo.getRetryAttemptsRemaining();
        return remaining != null && remaining <= 0
                || executionInfo.getJobData().getBoolean(KEY_FINAL_ATTEMPT, false);
    }

    private static boolean isLastRetry(final ExecutionInfo executionInfo) {
        final Integer remaining = executionInfo.getRetryAttemptsRemaining();
        return remaining != null && remaining == 1;
    }
}
