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
import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
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
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MICROSOFT_365;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.UNREACHABLE;
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
    private final RuntimeException serverError = new RuntimeException("Status code: 503 server error");
    private final FailureReasonClassifier failureReasonClassifier = new FailureReasonClassifier();

    private TransientFailurePolicy policy;

    @BeforeEach
    void createPolicyWithAFixedClock() {
        policy = new TransientFailurePolicy(
                statusService, new Clock(java.time.Clock.fixed(NOW, ZoneOffset.UTC)), dependencyCallLogger,
                failureReasonClassifier, RETRY_DURATIONS);
    }

    @Test
    void retryOrFail_should_retry_without_touching_status_when_attempts_remain() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.retryOrFail(
                id, 500, "did not recover", jobWithRetriesRemaining(2), RetryCause.GOV_NOTIFY_SEND, EMAIL, serverError);

        verifyNoInteractions(statusService);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void retryOrFail_should_retry_when_retry_attempts_are_unset() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.retryOrFail(
                id, 500, "did not recover", jobWithRetriesRemaining(null), RetryCause.GOV_NOTIFY_SEND, EMAIL,
                serverError);

        verifyNoInteractions(statusService);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void retryOrFail_should_mark_failed_and_complete_when_retries_are_exhausted() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.retryOrFail(
                id, 503, "did not recover", jobWithRetriesRemaining(0), RetryCause.GOV_NOTIFY_SEND, EMAIL, serverError);

        verify(statusService).markFailed(id, 503, "did not recover");
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void retryOrFail_should_reschedule_the_same_task_as_the_final_attempt_after_the_last_duration_when_one_attempt_remains() {
        final UUID id = UUID.randomUUID();
        final ExecutionInfo lastRetry = jobWithRetriesRemaining(1);

        final ExecutionInfo result = policy.retryOrFail(
                id, 503, "did not recover", lastRetry, RetryCause.GOV_NOTIFY_SEND, EMAIL, serverError);

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
                RetryCause.GOV_NOTIFY_SEND, EMAIL, serverError);

        verifyNoInteractions(statusService);
        assertThat(result.isShouldRetry()).isFalse();
        assertThat(result.getAssignedTaskStartTime()).isEqualTo(ZonedDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(43_200));
        assertThat(result.getJobData().getBoolean(TransientFailurePolicy.KEY_FINAL_ATTEMPT)).isTrue();
    }

    @Test
    void retryOrFail_should_mark_failed_and_complete_when_the_final_attempt_fails() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.retryOrFail(
                id, 503, "did not recover", finalAttemptJob(), RetryCause.GOV_NOTIFY_SEND, EMAIL, serverError);

        verify(statusService).markFailed(id, 503, "did not recover");
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void retryOrFail_should_log_the_dependency_call_fields_when_a_transient_failure_will_be_retried() {
        policy.retryOrFail(notificationId, 503, "did not recover", jobWithRetriesRemaining(2),
                List.of(60L, 300L), RetryCause.GOV_NOTIFY_SEND, LETTER, serverError);

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
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }

    @Test
    void retryOrFail_should_log_the_dependency_call_fields_when_scheduling_the_final_attempt() {
        policy.retryOrFail(notificationId, 502, "did not recover", jobWithRetriesRemaining(1),
                RetryCause.OFFICE365_SEND, EMAIL, serverError);

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
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }

    @Test
    void retryOrFail_should_log_the_dependency_call_fields_as_a_warning_when_retries_are_exhausted() {
        policy.retryOrFail(notificationId, 500, "did not recover", finalAttemptJob(),
                RetryCause.STATUS_CHECK, EMAIL, serverError);

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
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }

    @Test
    void retryOrFail_should_log_no_status_code_and_no_reason_when_the_failure_gives_neither() {
        final IllegalStateException illegalStateException = new IllegalStateException("unexpected");

        policy.retryOrFail(notificationId, null, "did not recover", jobWithRetriesRemaining(2),
                RetryCause.ATTACHMENT_DOWNLOAD, EMAIL, illegalStateException);

        verify(dependencyCallLogger).info(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Transient failure 'downloading the attachment from blob storage' for"
                + " notificationId '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (no status code) — will retry");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(BLOB_STORAGE);
        assertThat(dependencyCall.operation()).isEqualTo(DOWNLOAD);
        assertThat(dependencyCall.channel()).isEqualTo(EMAIL);
        assertThat(dependencyCall.callOutcome()).isEqualTo(TRANSIENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(empty());
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }

    @Test
    void retryOrFail_should_log_that_the_service_could_not_be_reached_when_gov_notify_gave_no_http_response() {
        final ConnectException connectException = new ConnectException("Connection refused");
        final GovNotifyException govNotifyException =
                new GovNotifyException(0, "java.net.ConnectException: Connection refused", connectException);

        policy.retryOrFail(notificationId, 0, "did not recover", jobWithRetriesRemaining(2),
                RetryCause.GOV_NOTIFY_SEND, EMAIL, govNotifyException);

        verify(dependencyCallLogger).info(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Transient failure 'invoking the Gov.Notify send API' for notificationId"
                + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (the service could not be reached) — will retry");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(GOV_UK_NOTIFY);
        assertThat(dependencyCall.operation()).isEqualTo(SEND);
        assertThat(dependencyCall.channel()).isEqualTo(EMAIL);
        assertThat(dependencyCall.callOutcome()).isEqualTo(TRANSIENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(empty());
        assertThat(dependencyCall.failureReason()).isEqualTo(of(UNREACHABLE));
    }

    @Test
    void retryOrFail_should_log_that_the_service_did_not_respond_when_scheduling_the_final_attempt_after_a_timeout() {
        final SocketTimeoutException socketTimeoutException = new SocketTimeoutException("Read timed out");
        final IOException ioException = new IOException("Material could not be reached", socketTimeoutException);

        policy.retryOrFail(notificationId, null, "did not recover", jobWithRetriesRemaining(1),
                List.of(60L, 300L), RetryCause.MATERIAL_DOWNLOAD, LETTER, ioException);

        verify(dependencyCallLogger).info(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Transient failure 'downloading the Material document' for notificationId"
                + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (the service did not respond) — scheduling the final"
                + " attempt");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.dependency()).isEqualTo(MATERIAL);
        assertThat(dependencyCall.operation()).isEqualTo(DOWNLOAD);
        assertThat(dependencyCall.channel()).isEqualTo(LETTER);
        assertThat(dependencyCall.callOutcome()).isEqualTo(TRANSIENT_FAILURE);
        assertThat(dependencyCall.statusCode()).isEqualTo(empty());
        assertThat(dependencyCall.failureReason()).isEqualTo(of(NO_RESPONSE));
    }

    @Test
    void retryOrFail_should_add_the_reason_to_the_failed_reason_when_retries_are_exhausted_without_a_status_code() {
        final ConnectException connectException = new ConnectException("Connection refused");
        final GovNotifyException govNotifyException =
                new GovNotifyException(0, "java.net.ConnectException: Connection refused", connectException);

        final ExecutionInfo result = policy.retryOrFail(notificationId, 0,
                "Gov.Notify send did not recover within the retry window", finalAttemptJob(),
                RetryCause.GOV_NOTIFY_SEND, EMAIL, govNotifyException);

        verify(statusService).markFailed(notificationId, 0,
                "Gov.Notify send did not recover within the retry window: the service could not be reached");
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(dependencyCallLogger).warn(dependencyCallCaptor.capture(), messageCaptor.capture());
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Transient failure 'invoking the Gov.Notify send API' for notificationId"
                + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (the service could not be reached) did not recover after"
                + " exhausting retries — marking FAILED");
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.statusCode()).isEqualTo(empty());
        assertThat(dependencyCall.failureReason()).isEqualTo(of(UNREACHABLE));
    }

    @Test
    void retryOrFail_should_give_no_reason_when_there_is_a_status_code_even_if_a_cause_is_an_input_output_failure() {
        final ConnectException connectException = new ConnectException("Connection reset");
        final RuntimeException runtimeException = new RuntimeException("Status code: 502", connectException);

        policy.retryOrFail(notificationId, 502, "did not recover", jobWithRetriesRemaining(2),
                RetryCause.OFFICE365_SEND, EMAIL, runtimeException);

        verify(dependencyCallLogger).info(dependencyCallCaptor.capture(), messageCaptor.capture());
        final DependencyCall dependencyCall = dependencyCallCaptor.getValue();
        assertThat(dependencyCall.statusCode()).isEqualTo(of(502));
        assertThat(dependencyCall.failureReason()).isEqualTo(empty());
    }

    @Test
    void pollAgainOrFail_should_poll_again_without_touching_status_when_attempts_remain() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.pollAgainOrFail(id, "no terminal status", jobWithRetriesRemaining(2));

        verifyNoInteractions(statusService);
        verifyNoInteractions(dependencyCallLogger);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void pollAgainOrFail_should_schedule_the_final_poll_after_the_last_duration_when_one_attempt_remains() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.pollAgainOrFail(
                id, "no terminal status", jobWithRetriesRemaining(1), List.of(60L, 43_200L));

        verifyNoInteractions(statusService);
        verifyNoInteractions(dependencyCallLogger);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isFalse();
        assertThat(result.getAssignedTaskStartTime()).isEqualTo(ZonedDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(43_200));
        assertThat(result.getJobData().getBoolean(TransientFailurePolicy.KEY_FINAL_ATTEMPT)).isTrue();
    }

    @Test
    void pollAgainOrFail_should_schedule_the_final_poll_after_the_last_default_duration_when_one_attempt_remains() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.pollAgainOrFail(id, "no terminal status", jobWithRetriesRemaining(1));

        assertThat(result.isShouldRetry()).isFalse();
        assertThat(result.getAssignedTaskStartTime()).isEqualTo(ZonedDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(14_400));
    }

    @Test
    void pollAgainOrFail_should_mark_failed_without_a_status_code_when_polls_are_exhausted() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo result = policy.pollAgainOrFail(id, "no terminal status", finalAttemptJob());

        verify(statusService).markFailed(id, null, "no terminal status");
        verifyNoInteractions(dependencyCallLogger);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
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
