package uk.gov.hmcts.cp.notification.task;

import static jakarta.json.spi.JsonProvider.provider;
import static java.time.ZoneOffset.UTC;
import static java.time.ZonedDateTime.ofInstant;
import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.task.RetryStep.EXHAUSTED;
import static uk.gov.hmcts.cp.notification.task.RetryStep.FINAL_ATTEMPT;
import static uk.gov.hmcts.cp.notification.task.RetryStep.RETRY;
import static uk.gov.hmcts.cp.notification.task.TaskRetrySchedule.KEY_FINAL_ATTEMPT;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.INPROGRESS;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.STARTED;

import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.time.Instant;
import java.time.ZonedDateTime;

class TaskRetryScheduleTest {
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private static final String NOTIFICATION_ID = "0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80";

    private final TaskRetrySchedule taskRetrySchedule =
            new TaskRetrySchedule(new Clock(java.time.Clock.fixed(NOW, UTC)));

    @Test
    void completed_should_finish_the_task() {
        final ExecutionInfo result = taskRetrySchedule.completed(jobWithRetriesRemaining(3));

        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        assertThat(result.isShouldRetry()).isFalse();
    }

    @Test
    void retry_should_keep_the_task_in_progress_and_ask_for_a_retry() {
        final ExecutionInfo result = taskRetrySchedule.retry(jobWithRetriesRemaining(3));

        assertThat(result.getExecutionStatus()).isEqualTo(INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void finalAttempt_should_reschedule_the_same_task_after_the_last_duration_without_a_retry() {
        final ExecutionInfo lastRetry = jobWithRetriesRemaining(1);

        final ExecutionInfo result = taskRetrySchedule.finalAttempt(lastRetry, of(60L, 43_200L));

        final ZonedDateTime now = ofInstant(NOW, UTC);
        assertThat(result.getExecutionStatus()).isEqualTo(INPROGRESS);
        assertThat(result.isShouldRetry()).isFalse();
        assertThat(result.getAssignedTaskName()).isEqualTo(SendEmailTask.TASK_NAME);
        assertThat(result.getAssignedTaskStartTime()).isEqualTo(now.plusSeconds(43_200));
        assertThat(result.getRetryAttemptsRemaining()).isEqualTo(1);
        final JsonObject jobData = result.getJobData();
        assertThat(jobData.getBoolean(KEY_FINAL_ATTEMPT)).isTrue();
        assertThat(jobData.getString("notificationId")).isEqualTo(NOTIFICATION_ID);
    }

    @Test
    void nextStep_should_be_exhausted_with_no_retry_attempts_remaining() {
        assertThat(taskRetrySchedule.nextStep(jobWithRetriesRemaining(0))).isEqualTo(EXHAUSTED);
    }

    @Test
    void nextStep_should_be_exhausted_on_the_final_attempt_even_with_a_retry_attempt_remaining() {
        assertThat(taskRetrySchedule.nextStep(finalAttemptJob())).isEqualTo(EXHAUSTED);
    }

    @Test
    void nextStep_should_be_the_final_attempt_with_one_retry_attempt_remaining() {
        assertThat(taskRetrySchedule.nextStep(jobWithRetriesRemaining(1))).isEqualTo(FINAL_ATTEMPT);
    }

    @Test
    void nextStep_should_be_retry_with_more_than_one_retry_attempt_remaining() {
        assertThat(taskRetrySchedule.nextStep(jobWithRetriesRemaining(2))).isEqualTo(RETRY);
    }

    @Test
    void nextStep_should_be_retry_when_retry_attempts_are_not_set() {
        assertThat(taskRetrySchedule.nextStep(jobWithRetriesRemaining(null))).isEqualTo(RETRY);
    }

    private static ExecutionInfo finalAttemptJob() {
        final JsonObject jobData = provider().createObjectBuilder()
                .add("notificationId", NOTIFICATION_ID)
                .add(KEY_FINAL_ATTEMPT, true)
                .build();
        return jobWith(jobData, 1);
    }

    private static ExecutionInfo jobWithRetriesRemaining(final Integer retryAttemptsRemaining) {
        final JsonObject jobData = provider().createObjectBuilder()
                .add("notificationId", NOTIFICATION_ID)
                .build();
        return jobWith(jobData, retryAttemptsRemaining);
    }

    private static ExecutionInfo jobWith(final JsonObject jobData, final Integer retryAttemptsRemaining) {
        return executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(SendEmailTask.TASK_NAME)
                .withAssignedTaskStartTime(ofInstant(NOW, UTC))
                .withExecutionStatus(STARTED)
                .withRetryAttemptsRemaining(retryAttemptsRemaining)
                .build();
    }
}
