package uk.gov.hmcts.cp.notification.task;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.logging.DependencyCall;
import uk.gov.hmcts.cp.notification.logging.DependencyCallLogger;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.TRANSIENT_FAILURE;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.Dependency.BLOB_STORAGE;
import static uk.gov.hmcts.cp.notification.logging.Dependency.GOV_UK_NOTIFY;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MICROSOFT_365;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;
import static uk.gov.hmcts.cp.notification.logging.Operation.STATUS_CHECK;

@ExtendWith(MockitoExtension.class)
class TransientFailurePolicyTest {

    private static final List<Long> RETRY_DURATIONS = List.of(60L, 300L, 14_400L);
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

    @Mock
    private NotificationStatusService statusService;
    @Mock
    private DependencyCallLogger dependencyCallLogger;
    @Captor
    private ArgumentCaptor<DependencyCall> dependencyCallCaptor;
    @Captor
    private ArgumentCaptor<String> messageCaptor;

    private final UUID notificationId = UUID.fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80");

    private TransientFailurePolicy policy;

    @BeforeEach
    void setUp() {
        policy = new TransientFailurePolicy(
                statusService, new Clock(java.time.Clock.fixed(NOW, ZoneOffset.UTC)), dependencyCallLogger,
                RETRY_DURATIONS);
    }

    @Test
    void retryOrFail_should_retry_without_touching_status_when_attempts_remain() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.retryOrFail(
                id, 500, "did not recover", jobWithRetriesRemaining(2), RetryCause.GOV_NOTIFY_SEND, EMAIL);

        verifyNoInteractions(statusService);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void retryOrFail_should_retry_when_retry_attempts_are_unset() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.retryOrFail(
                id, 500, "did not recover", jobWithRetriesRemaining(null), RetryCause.GOV_NOTIFY_SEND, EMAIL);

        verifyNoInteractions(statusService);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void retryOrFail_should_mark_failed_and_complete_when_retries_are_exhausted() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.retryOrFail(
                id, 503, "did not recover", jobWithRetriesRemaining(0), RetryCause.GOV_NOTIFY_SEND, EMAIL);

        verify(statusService).markFailed(id, 503, "did not recover");
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void retryOrFail_should_reschedule_the_same_task_as_the_final_attempt_after_the_last_duration_when_one_attempt_remains() {
        final UUID id = UUID.randomUUID();
        final ExecutionInfo lastRetry = jobWithRetriesRemaining(1);

        final ExecutionInfo result = policy.retryOrFail(
                id, 503, "did not recover", lastRetry, RetryCause.GOV_NOTIFY_SEND, EMAIL);

        verifyNoInteractions(statusService);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isFalse();
        assertThat(result.getAssignedTaskName()).isEqualTo(SendEmailTask.TASK_NAME);
        assertThat(result.getAssignedTaskStartTime()).isEqualTo(ZonedDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(14_400));
        assertThat(result.getJobData().getBoolean(TransientFailurePolicy.KEY_FINAL_ATTEMPT)).isTrue();
        assertThat(result.getJobData().getString("notificationId"))
                .isEqualTo(lastRetry.getJobData().getString("notificationId"));
    }

    @Test
    void retryOrFail_should_schedule_the_final_attempt_after_the_last_of_the_tasks_own_durations_when_given() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.retryOrFail(
                id, 503, "did not recover", jobWithRetriesRemaining(1), List.of(60L, 43_200L),
                RetryCause.GOV_NOTIFY_SEND, EMAIL);

        verifyNoInteractions(statusService);
        assertThat(result.isShouldRetry()).isFalse();
        assertThat(result.getAssignedTaskStartTime()).isEqualTo(ZonedDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(43_200));
        assertThat(result.getJobData().getBoolean(TransientFailurePolicy.KEY_FINAL_ATTEMPT)).isTrue();
    }

    @Test
    void retryOrFail_should_mark_failed_and_complete_when_the_final_attempt_fails() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.retryOrFail(
                id, 503, "did not recover", finalAttemptJob(), RetryCause.GOV_NOTIFY_SEND, EMAIL);

        verify(statusService).markFailed(id, 503, "did not recover");
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void retryOrFail_should_poll_again_without_touching_status_when_still_in_progress_and_attempts_remain() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.retryOrFail(
                id, null, "no terminal status", jobWithRetriesRemaining(2), RetryCause.STILL_IN_PROGRESS, EMAIL);

        verifyNoInteractions(statusService);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void retryOrFail_should_schedule_the_final_poll_after_the_last_duration_when_still_in_progress_and_one_attempt_remains() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.retryOrFail(
                id, null, "no terminal status", jobWithRetriesRemaining(1), List.of(60L, 43_200L),
                RetryCause.STILL_IN_PROGRESS, EMAIL);

        verifyNoInteractions(statusService);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isFalse();
        assertThat(result.getAssignedTaskStartTime()).isEqualTo(ZonedDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(43_200));
        assertThat(result.getJobData().getBoolean(TransientFailurePolicy.KEY_FINAL_ATTEMPT)).isTrue();
    }

    @Test
    void retryOrFail_should_mark_failed_without_a_status_code_when_still_in_progress_after_polls_are_exhausted() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.retryOrFail(
                id, null, "no terminal status", finalAttemptJob(), RetryCause.STILL_IN_PROGRESS, EMAIL);

        verify(statusService).markFailed(id, null, "no terminal status");
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void retryOrFail_should_log_the_dependency_call_fields_when_a_transient_failure_will_be_retried() {
        policy.retryOrFail(notificationId, 503, "did not recover", jobWithRetriesRemaining(2),
                List.of(60L, 300L), RetryCause.GOV_NOTIFY_SEND, LETTER);

        verify(dependencyCallLogger).info(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Transient failure 'invoking the Gov.Notify send API' for notificationId"
                + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (statusCode '503') — will retry");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(GOV_UK_NOTIFY);
        assertThat(dependencyCall.operation()).isEqualTo(SEND);
        assertThat(dependencyCall.channel()).isEqualTo(LETTER);
        assertThat(dependencyCall.callOutcome()).isEqualTo(TRANSIENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(of(503));
    }

    @Test
    void retryOrFail_should_log_the_dependency_call_fields_when_scheduling_the_final_attempt() {
        policy.retryOrFail(notificationId, 502, "did not recover", jobWithRetriesRemaining(1),
                RetryCause.OFFICE365_SEND, EMAIL);

        verify(dependencyCallLogger).info(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Transient failure 'invoking the Office 365 send API' for notificationId"
                + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (statusCode '502') — scheduling the final attempt");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(MICROSOFT_365);
        assertThat(dependencyCall.operation()).isEqualTo(SEND);
        assertThat(dependencyCall.channel()).isEqualTo(EMAIL);
        assertThat(dependencyCall.callOutcome()).isEqualTo(TRANSIENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(of(502));
    }

    @Test
    void retryOrFail_should_log_the_dependency_call_fields_as_a_warning_when_retries_are_exhausted() {
        policy.retryOrFail(notificationId, 500, "did not recover", finalAttemptJob(),
                RetryCause.STATUS_CHECK, EMAIL);

        verify(dependencyCallLogger).warn(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Transient failure 'invoking the Gov.Notify status API' for notificationId"
                + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (statusCode '500') did not recover after exhausting retries"
                + " — marking FAILED");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(GOV_UK_NOTIFY);
        assertThat(dependencyCall.operation()).isEqualTo(STATUS_CHECK);
        assertThat(dependencyCall.channel()).isEqualTo(EMAIL);
        assertThat(dependencyCall.callOutcome()).isEqualTo(TRANSIENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(of(500));
    }

    @Test
    void retryOrFail_should_log_no_status_code_when_the_dependency_gave_none() {
        policy.retryOrFail(notificationId, null, "did not recover", jobWithRetriesRemaining(2),
                RetryCause.ATTACHMENT_DOWNLOAD, EMAIL);

        verify(dependencyCallLogger).info(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Transient failure 'downloading the attachment from blob storage' for"
                + " notificationId '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (statusCode 'null') — will retry");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(BLOB_STORAGE);
        assertThat(dependencyCall.operation()).isEqualTo(DOWNLOAD);
        assertThat(dependencyCall.channel()).isEqualTo(EMAIL);
        assertThat(dependencyCall.callOutcome()).isEqualTo(TRANSIENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(empty());
    }

    @Test
    void retryOrFail_should_log_no_dependency_call_when_gov_notify_reports_the_notification_still_in_progress() {
        policy.retryOrFail(UUID.randomUUID(), null, "no terminal status", jobWithRetriesRemaining(2),
                RetryCause.STILL_IN_PROGRESS, LETTER);

        verifyNoInteractions(dependencyCallLogger);
    }

    @Test
    void fail_should_mark_failed_and_complete_without_retry() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.fail(id, 400, "bad request", jobWithRetriesRemaining(3));

        verify(statusService).markFailed(id, 400, "bad request");
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.isShouldRetry()).isFalse();
    }

    @Test
    void fail_should_accept_a_null_status_code() {
        final UUID id = UUID.randomUUID();

        policy.fail(id, null, "terminal status", jobWithRetriesRemaining(3));

        verify(statusService).markFailed(id, null, "terminal status");
    }

    private static ExecutionInfo finalAttemptJob() {
        return jobWith(Json.createObjectBuilder()
                .add("notificationId", UUID.randomUUID().toString())
                .add(TransientFailurePolicy.KEY_FINAL_ATTEMPT, true)
                .build(), 1);
    }

    private static ExecutionInfo jobWithRetriesRemaining(final Integer retriesRemaining) {
        return jobWith(Json.createObjectBuilder().add("notificationId", UUID.randomUUID().toString()).build(),
                retriesRemaining);
    }

    private static ExecutionInfo jobWith(final JsonObject jobData, final Integer retriesRemaining) {
        return ExecutionInfo.executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(SendEmailTask.TASK_NAME)
                .withAssignedTaskStartTime(ZonedDateTime.now(ZoneOffset.UTC))
                .withExecutionStatus(ExecutionStatus.STARTED)
                .withRetryAttemptsRemaining(retriesRemaining)
                .build();
    }
}
