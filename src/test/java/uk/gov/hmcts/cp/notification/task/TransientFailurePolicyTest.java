package uk.gov.hmcts.cp.notification.task;

import static ch.qos.logback.classic.Level.INFO;
import static ch.qos.logback.classic.Level.WARN;
import static jakarta.json.spi.JsonProvider.provider;
import static java.time.ZoneOffset.UTC;
import static java.time.ZonedDateTime.ofInstant;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.UUID.fromString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static uk.gov.hmcts.cp.notification.integration.testdata.TransientFailurePolicyFactory.aTransientFailurePolicy;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_UNAVAILABLE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_FAILED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_UNREACHABLE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.STATUS_UNKNOWN;
import static uk.gov.hmcts.cp.notification.task.RetryCause.GOV_NOTIFY_SEND;
import static uk.gov.hmcts.cp.notification.task.RetryCause.MATERIAL_DOWNLOAD;
import static uk.gov.hmcts.cp.notification.task.TaskRetrySchedule.KEY_FINAL_ATTEMPT;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.INPROGRESS;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.STARTED;

import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.KeyValuePair;

import uk.gov.hmcts.cp.notification.logging.CapturedLogs;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.net.ConnectException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class TransientFailurePolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private static final UUID NOTIFICATION_ID = fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80");
    private static final List<Long> RETRY_DURATIONS_SECS = List.of(60L, 300L, 14_400L);

    private final RuntimeException serverError = new RuntimeException("Status code: 503 server error");
    private final TransientFailure transientFailure = new TransientFailure(
            NOTIFICATION_ID, GOV_NOTIFY_SEND, EMAIL, of(503), serverError, "did not recover");

    @Mock
    private NotificationStatusService statusService;

    private TransientFailurePolicy transientFailurePolicy;

    @BeforeEach
    void createPolicyWithRealClassifiersAndRetryScheduleOnAFixedClock() {
        final TaskRetrySchedule taskRetrySchedule = new TaskRetrySchedule(new Clock(java.time.Clock.fixed(NOW, UTC)));
        transientFailurePolicy = aTransientFailurePolicy(statusService, taskRetrySchedule);
    }

    @Nested
    class RetryOrFail {

        @Test
        void should_retry_and_log_it_with_the_call_fields_while_more_than_one_retry_attempt_remains() {
            final ExecutionInfo result;
            final ILoggingEvent loggingEvent;
            try (CapturedLogs capturedLogs = captureLogsOf(TransientFailurePolicy.class)) {
                result = transientFailurePolicy.retryOrFail(
                        transientFailure, jobWithRetriesRemaining(2), RETRY_DURATIONS_SECS);
                loggingEvent = onlyEventIn(capturedLogs);
            }

            assertThat(result.getExecutionStatus()).isEqualTo(INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
            verifyNoInteractions(statusService);
            assertThat(loggingEvent.getLevel()).isEqualTo(INFO);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("""
                    Transient failure 'invoking the Gov.Notify send API' for notificationId \
                    '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (statusCode '503') — will retry""");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("gov-uk-notify");
            assertThat(fields.get("operation")).isEqualTo("send");
            assertThat(fields.get("channel")).isEqualTo("email");
            assertThat(fields.get("outcome")).isEqualTo("transient-failure");
            assertThat(fields.get("statusCode")).isEqualTo(503);
        }

        @Test
        void should_schedule_the_final_attempt_after_the_last_duration_when_one_retry_attempt_remains() {
            final ExecutionInfo result;
            final ILoggingEvent loggingEvent;
            try (CapturedLogs capturedLogs = captureLogsOf(TransientFailurePolicy.class)) {
                result = transientFailurePolicy.retryOrFail(
                        transientFailure, jobWithRetriesRemaining(1), RETRY_DURATIONS_SECS);
                loggingEvent = onlyEventIn(capturedLogs);
            }

            assertThat(result.isShouldRetry()).isFalse();
            assertThat(result.getAssignedTaskStartTime()).isEqualTo(ofInstant(NOW, UTC).plusSeconds(14_400));
            assertThat(result.getJobData().getBoolean(KEY_FINAL_ATTEMPT)).isTrue();
            verifyNoInteractions(statusService);
            assertThat(loggingEvent.getLevel()).isEqualTo(INFO);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("""
                    Transient failure 'invoking the Gov.Notify send API' for notificationId \
                    '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (statusCode '503') — scheduling the final attempt""");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields.get("outcome")).isEqualTo("transient-failure");
        }

        @Test
        void should_mark_failed_with_the_exhausted_reason_and_the_classified_outcome_once_retries_are_exhausted() {
            final ExecutionInfo result;
            final ILoggingEvent loggingEvent;
            try (CapturedLogs capturedLogs = captureLogsOf(TransientFailurePolicy.class)) {
                result = transientFailurePolicy.retryOrFail(transientFailure, finalAttemptJob(), RETRY_DURATIONS_SECS);
                loggingEvent = onlyEventIn(capturedLogs);
            }

            assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
            verify(statusService).markFailed(
                    new NotificationFailure(NOTIFICATION_ID, EMAIL, 503, "did not recover", PROVIDER_FAILED));
            assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("""
                    Transient failure 'invoking the Gov.Notify send API' for notificationId \
                    '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (statusCode '503') did not recover after exhausting \
                    retries — marking FAILED""");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields.get("dependency")).isEqualTo("gov-uk-notify");
            assertThat(fields.get("statusCode")).isEqualTo(503);
        }

        @Test
        void should_mark_failed_as_provider_unreachable_with_no_status_code_when_the_provider_was_never_reached() {
            final ConnectException connectException = new ConnectException("Connection refused");
            final TransientFailure unreachable = new TransientFailure(
                    NOTIFICATION_ID, GOV_NOTIFY_SEND, EMAIL, empty(), connectException, "did not recover");

            final ExecutionInfo result;
            final ILoggingEvent loggingEvent;
            try (CapturedLogs capturedLogs = captureLogsOf(TransientFailurePolicy.class)) {
                result = transientFailurePolicy.retryOrFail(unreachable, finalAttemptJob(), RETRY_DURATIONS_SECS);
                loggingEvent = onlyEventIn(capturedLogs);
            }

            assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
            verify(statusService).markFailed(
                    new NotificationFailure(NOTIFICATION_ID, EMAIL, null, "did not recover", PROVIDER_UNREACHABLE));
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields.containsKey("statusCode")).isFalse();
            assertThat(fields.get("reason")).isEqualTo("unreachable");
        }

        @Test
        void should_mark_failed_as_attachment_unavailable_when_a_download_never_recovers() {
            final ConnectException connectException = new ConnectException("Connection refused");
            final TransientFailure download = new TransientFailure(
                    NOTIFICATION_ID, MATERIAL_DOWNLOAD, LETTER, empty(), connectException, "did not recover");

            transientFailurePolicy.retryOrFail(download, finalAttemptJob(), RETRY_DURATIONS_SECS);

            verify(statusService).markFailed(
                    new NotificationFailure(NOTIFICATION_ID, LETTER, null, "did not recover", ATTACHMENT_UNAVAILABLE));
        }

        @Test
        void should_log_why_and_no_status_code_when_the_service_could_not_be_reached() {
            final ConnectException connectException = new ConnectException("Connection refused");
            final TransientFailure unreachable = new TransientFailure(
                    NOTIFICATION_ID, MATERIAL_DOWNLOAD, LETTER, empty(), connectException, "did not recover");

            final ILoggingEvent loggingEvent;
            try (CapturedLogs capturedLogs = captureLogsOf(TransientFailurePolicy.class)) {
                transientFailurePolicy.retryOrFail(unreachable, jobWithRetriesRemaining(2), RETRY_DURATIONS_SECS);
                loggingEvent = onlyEventIn(capturedLogs);
            }

            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("""
                    Transient failure 'downloading the Material document' for notificationId \
                    '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (the service could not be reached) — will retry""");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("material");
            assertThat(fields.get("operation")).isEqualTo("download");
            assertThat(fields.get("channel")).isEqualTo("letter");
            assertThat(fields.get("outcome")).isEqualTo("transient-failure");
            assertThat(fields.get("reason")).isEqualTo("unreachable");
        }

        @Test
        void should_say_there_was_no_status_code_when_nothing_says_why() {
            final IllegalStateException illegalStateException = new IllegalStateException("unexpected");
            final TransientFailure unexplained = new TransientFailure(
                    NOTIFICATION_ID, GOV_NOTIFY_SEND, EMAIL, empty(), illegalStateException, "did not recover");

            final ILoggingEvent loggingEvent;
            try (CapturedLogs capturedLogs = captureLogsOf(TransientFailurePolicy.class)) {
                transientFailurePolicy.retryOrFail(unexplained, jobWithRetriesRemaining(2), RETRY_DURATIONS_SECS);
                loggingEvent = onlyEventIn(capturedLogs);
            }

            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).contains("(no status code) — will retry");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(4);
            assertThat(fields.containsKey("statusCode")).isFalse();
            assertThat(fields.containsKey("reason")).isFalse();
        }
    }

    @Nested
    class PollAgainOrFail {

        @Test
        void should_poll_again_while_more_than_one_retry_attempt_remains() {
            final ExecutionInfo result;
            final ILoggingEvent loggingEvent;
            try (CapturedLogs capturedLogs = captureLogsOf(TransientFailurePolicy.class)) {
                result = transientFailurePolicy.pollAgainOrFail(
                        NOTIFICATION_ID, LETTER, "no terminal status", jobWithRetriesRemaining(2), RETRY_DURATIONS_SECS);
                loggingEvent = onlyEventIn(capturedLogs);
            }

            assertThat(result.getExecutionStatus()).isEqualTo(INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
            verifyNoInteractions(statusService);
            assertThat(loggingEvent.getLevel()).isEqualTo(INFO);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo(
                    "Notification '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' still in progress at Gov.Notify — polling again");
            assertThat(loggingEvent.getKeyValuePairs()).isNullOrEmpty();
        }

        @Test
        void should_schedule_the_final_poll_after_the_last_duration_when_one_retry_attempt_remains() {
            final ExecutionInfo result;
            final ILoggingEvent loggingEvent;
            try (CapturedLogs capturedLogs = captureLogsOf(TransientFailurePolicy.class)) {
                result = transientFailurePolicy.pollAgainOrFail(
                        NOTIFICATION_ID, LETTER, "no terminal status", jobWithRetriesRemaining(1), RETRY_DURATIONS_SECS);
                loggingEvent = onlyEventIn(capturedLogs);
            }

            assertThat(result.isShouldRetry()).isFalse();
            assertThat(result.getAssignedTaskStartTime()).isEqualTo(ofInstant(NOW, UTC).plusSeconds(14_400));
            verifyNoInteractions(statusService);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("""
                    Notification '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' still in progress at Gov.Notify \
                    — scheduling the final status poll""");
        }

        @Test
        void should_mark_failed_as_status_unknown_without_a_status_code_once_polls_are_exhausted() {
            final ExecutionInfo result;
            final ILoggingEvent loggingEvent;
            try (CapturedLogs capturedLogs = captureLogsOf(TransientFailurePolicy.class)) {
                result = transientFailurePolicy.pollAgainOrFail(
                        NOTIFICATION_ID, LETTER, "no terminal status", finalAttemptJob(), RETRY_DURATIONS_SECS);
                loggingEvent = onlyEventIn(capturedLogs);
            }

            assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
            verify(statusService).markFailed(
                    new NotificationFailure(NOTIFICATION_ID, LETTER, null, "no terminal status", STATUS_UNKNOWN));
            assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("""
                    Notification '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' still in progress at Gov.Notify after \
                    exhausting status polls — marking FAILED""");
        }
    }

    private static ILoggingEvent onlyEventIn(final CapturedLogs capturedLogs) {
        final List<ILoggingEvent> loggingEvents = capturedLogs.events();
        assertThat(loggingEvents).hasSize(1);
        return loggingEvents.getFirst();
    }

    private static Map<String, Object> fieldsOf(final ILoggingEvent loggingEvent) {
        final Map<String, Object> fields = new HashMap<>();
        final List<KeyValuePair> keyValuePairs = loggingEvent.getKeyValuePairs();
        for (final KeyValuePair keyValuePair : keyValuePairs) {
            fields.put(keyValuePair.key, keyValuePair.value);
        }
        return fields;
    }

    private static ExecutionInfo finalAttemptJob() {
        final JsonObject jobData = provider().createObjectBuilder()
                .add("notificationId", NOTIFICATION_ID.toString())
                .add(KEY_FINAL_ATTEMPT, true)
                .build();
        return jobWith(jobData, 1);
    }

    private static ExecutionInfo jobWithRetriesRemaining(final Integer retryAttemptsRemaining) {
        final JsonObject jobData = provider().createObjectBuilder()
                .add("notificationId", NOTIFICATION_ID.toString())
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
