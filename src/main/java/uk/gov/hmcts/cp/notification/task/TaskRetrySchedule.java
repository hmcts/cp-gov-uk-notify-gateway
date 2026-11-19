package uk.gov.hmcts.cp.notification.task;

import static jakarta.json.spi.JsonProvider.provider;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;
import static uk.gov.hmcts.cp.notification.task.RetryStep.EXHAUSTED;
import static uk.gov.hmcts.cp.notification.task.RetryStep.FINAL_ATTEMPT;
import static uk.gov.hmcts.cp.notification.task.RetryStep.RETRY;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.INPROGRESS;

import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObject;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * What a notification task hands back to cp-task-manager: finished, retry, or one final attempt; and where the
 * current run stands in its retries.
 */
@Component
public class TaskRetrySchedule {
    public static final String KEY_FINAL_ATTEMPT = "finalAttempt";

    // Created once: Json.createObjectBuilder() looks up a new JSON provider on every call.
    private static final JsonBuilderFactory JSON_BUILDER_FACTORY = provider().createBuilderFactory(null);

    private final Clock clock;

    public TaskRetrySchedule(final Clock clock) {
        this.clock = clock;
    }

    public ExecutionInfo completed(final ExecutionInfo executionInfo) {
        return executionInfo().from(executionInfo)
                .withExecutionStatus(COMPLETED)
                .build();
    }

    public ExecutionInfo retry(final ExecutionInfo executionInfo) {
        return executionInfo().from(executionInfo)
                .withExecutionStatus(INPROGRESS)
                .withShouldRetry(true)
                .build();
    }

    // Workaround for cp-task-manager, which never picks up a job with no retry attempts remaining: the run that
    // would see retries exhausted never happens, so the notification would stay QUEUED for good. Instead the last
    // retry reschedules the same task (INPROGRESS, no retry), which keeps its remaining count, and flags it as the
    // final attempt so a failure there marks it FAILED.
    public ExecutionInfo finalAttempt(final ExecutionInfo executionInfo, final List<Long> retryDurationsSecs) {
        final JsonObject jobData = executionInfo.getJobData();
        final JsonObject finalAttemptJobData = JSON_BUILDER_FACTORY.createObjectBuilder(jobData)
                .add(KEY_FINAL_ATTEMPT, true)
                .build();
        final Long lastRetryDurationSecs = retryDurationsSecs.getLast();
        final ZonedDateTime now = clock.zonedDateTime();
        final ZonedDateTime finalAttemptStartTime = now.plusSeconds(lastRetryDurationSecs);
        return executionInfo().from(executionInfo)
                .withJobData(finalAttemptJobData)
                .withExecutionStatus(INPROGRESS)
                .withShouldRetry(false)
                .withAssignedTaskStartTime(finalAttemptStartTime)
                .build();
    }

    public RetryStep nextStep(final ExecutionInfo executionInfo) {
        final RetryStep retryStep;
        if (retriesExhausted(executionInfo)) {
            retryStep = EXHAUSTED;
        } else if (isLastRetry(executionInfo)) {
            retryStep = FINAL_ATTEMPT;
        } else {
            retryStep = RETRY;
        }
        return retryStep;
    }

    private boolean retriesExhausted(final ExecutionInfo executionInfo) {
        final Integer retryAttemptsRemaining = executionInfo.getRetryAttemptsRemaining();
        final JsonObject jobData = executionInfo.getJobData();
        final boolean noRetryAttemptsRemaining = retryAttemptsRemaining != null && retryAttemptsRemaining <= 0;
        final boolean finalAttempt = jobData.getBoolean(KEY_FINAL_ATTEMPT, false);
        return noRetryAttemptsRemaining || finalAttempt;
    }

    private boolean isLastRetry(final ExecutionInfo executionInfo) {
        final Integer retryAttemptsRemaining = executionInfo.getRetryAttemptsRemaining();
        return retryAttemptsRemaining != null && retryAttemptsRemaining == 1;
    }
}
