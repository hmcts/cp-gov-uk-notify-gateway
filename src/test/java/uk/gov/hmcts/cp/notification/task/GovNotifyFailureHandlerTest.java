package uk.gov.hmcts.cp.notification.task;

import static ch.qos.logback.classic.Level.WARN;
import static jakarta.json.spi.JsonProvider.provider;
import static java.time.ZoneOffset.UTC;
import static java.time.ZonedDateTime.ofInstant;
import static java.util.Optional.ofNullable;
import static java.util.UUID.fromString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static uk.gov.hmcts.cp.notification.integration.testdata.TransientFailurePolicyFactory.aGovNotifyFailureHandler;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_FAILED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_UNREACHABLE;
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
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class GovNotifyFailureHandlerTest {
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final UUID NOTIFICATION_ID = fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80");
    private static final List<Long> RETRY_DURATIONS_SECS = List.of(60L, 300L);

    @Mock
    private NotificationStatusService statusService;

    private GovNotifyFailureHandler govNotifyFailureHandler;

    @BeforeEach
    void createHandlerWithRealFailureHandling() {
        govNotifyFailureHandler = aGovNotifyFailureHandler(statusService, new TaskRetrySchedule(new Clock()));
    }

    @Nested
    class SendFailed {

        @Test
        void should_retry_a_failure_that_may_recover_without_recording_anything() {
            final GovNotifyException serverError = new GovNotifyException(503, "Status code: 503", null);

            final ExecutionInfo result = govNotifyFailureHandler.sendFailed(
                    NOTIFICATION_ID, EMAIL, serverError, jobWithRetriesRemaining(3), RETRY_DURATIONS_SECS);

            assertThat(result.getExecutionStatus()).isEqualTo(INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
            verifyNoInteractions(statusService);
        }

        @Test
        void should_record_the_email_send_reason_when_the_retries_run_out() {
            final GovNotifyException serverError = new GovNotifyException(503, "Status code: 503", null);

            govNotifyFailureHandler.sendFailed(
                    NOTIFICATION_ID, EMAIL, serverError, jobWithRetriesRemaining(0), RETRY_DURATIONS_SECS);

            verify(statusService).markFailed(new NotificationFailure(
                    NOTIFICATION_ID, EMAIL, 503, "Gov.Notify send did not recover within the retry window",
                    PROVIDER_FAILED));
        }

        @Test
        void should_record_the_letter_send_reason_when_the_retries_run_out() {
            final GovNotifyException serverError = new GovNotifyException(503, "Status code: 503", null);

            govNotifyFailureHandler.sendFailed(
                    NOTIFICATION_ID, LETTER, serverError, jobWithRetriesRemaining(0), RETRY_DURATIONS_SECS);

            verify(statusService).markFailed(new NotificationFailure(
                    NOTIFICATION_ID, LETTER, 503, "Gov.Notify letter send did not recover within the retry window",
                    PROVIDER_FAILED));
        }

        @Test
        void should_mark_failed_as_provider_rejected_and_log_the_send_when_gov_notify_rejects_it() {
            final GovNotifyException badRequest = new GovNotifyException(400, "bad request", null);

            final ExecutionInfo result;
            final ILoggingEvent loggingEvent;
            try (CapturedLogs capturedLogs = captureLogsOf(GovNotifyFailureHandler.class)) {
                result = govNotifyFailureHandler.sendFailed(
                        NOTIFICATION_ID, LETTER, badRequest, jobWithRetriesRemaining(3), RETRY_DURATIONS_SECS);
                loggingEvent = onlyEventIn(capturedLogs);
            }

            assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
            verify(statusService).markFailed(
                    new NotificationFailure(NOTIFICATION_ID, LETTER, 400, "bad request", PROVIDER_REJECTED));
            assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("""
                    Permanent Gov.Notify send failure for letter notification \
                    '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (http '400') — marking FAILED""");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("gov-uk-notify");
            assertThat(fields.get("operation")).isEqualTo("send");
            assertThat(fields.get("channel")).isEqualTo("letter");
            assertThat(fields.get("outcome")).isEqualTo("permanent-failure");
            assertThat(fields.get("statusCode")).isEqualTo(400);
        }
    }

    @Nested
    class StatusCheckFailed {

        @Test
        void should_retry_a_failure_that_may_recover_without_recording_anything() {
            final GovNotifyException serverError = new GovNotifyException(503, "Status code: 503", null);

            final ExecutionInfo result = govNotifyFailureHandler.statusCheckFailed(
                    NOTIFICATION_ID, EMAIL, serverError, jobWithRetriesRemaining(3), RETRY_DURATIONS_SECS);

            assertThat(result.getExecutionStatus()).isEqualTo(INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
            verifyNoInteractions(statusService);
        }

        @Test
        void should_record_the_status_polling_reason_when_the_retries_run_out() {
            final GovNotifyException serverError = new GovNotifyException(503, "Status code: 503", null);

            govNotifyFailureHandler.statusCheckFailed(
                    NOTIFICATION_ID, LETTER, serverError, jobWithRetriesRemaining(0), RETRY_DURATIONS_SECS);

            verify(statusService).markFailed(new NotificationFailure(
                    NOTIFICATION_ID, LETTER, 503, "Gov.Notify status polling did not recover within the retry window",
                    PROVIDER_FAILED));
        }

        @Test
        void should_mark_failed_as_provider_rejected_and_log_the_status_code_when_gov_notify_rejects_the_check() {
            final GovNotifyException notFound = new GovNotifyException(404, "not found", null);

            final ExecutionInfo result;
            final ILoggingEvent loggingEvent;
            try (CapturedLogs capturedLogs = captureLogsOf(GovNotifyFailureHandler.class)) {
                result = govNotifyFailureHandler.statusCheckFailed(
                        NOTIFICATION_ID, LETTER, notFound, jobWithRetriesRemaining(3), RETRY_DURATIONS_SECS);
                loggingEvent = onlyEventIn(capturedLogs);
            }

            assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
            assertThat(result.isShouldRetry()).isFalse();
            verify(statusService).markFailed(
                    new NotificationFailure(NOTIFICATION_ID, LETTER, 404, "not found", PROVIDER_REJECTED));
            assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("""
                    Permanent Gov.Notify status-check failure for letter notification \
                    '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (http '404') — marking FAILED""");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("gov-uk-notify");
            assertThat(fields.get("operation")).isEqualTo("status-check");
            assertThat(fields.get("channel")).isEqualTo("letter");
            assertThat(fields.get("outcome")).isEqualTo("permanent-failure");
            assertThat(fields.get("statusCode")).isEqualTo(404);
        }

        @Test
        void should_mark_failed_as_provider_unreachable_and_log_why_when_gov_notify_could_not_be_reached() {
            final ConnectException connectException = new ConnectException("Connection refused");
            final GovNotifyException unreachable =
                    new GovNotifyException(0, "java.net.ConnectException: Connection refused", connectException);

            final ILoggingEvent loggingEvent;
            try (CapturedLogs capturedLogs = captureLogsOf(GovNotifyFailureHandler.class)) {
                govNotifyFailureHandler.statusCheckFailed(
                        NOTIFICATION_ID, EMAIL, unreachable, jobWithRetriesRemaining(3), RETRY_DURATIONS_SECS);
                loggingEvent = onlyEventIn(capturedLogs);
            }

            verify(statusService).markFailed(new NotificationFailure(
                    NOTIFICATION_ID, EMAIL, 0, "java.net.ConnectException: Connection refused", PROVIDER_UNREACHABLE));
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).contains("(http 'none')");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("channel")).isEqualTo("email");
            assertThat(fields.get("reason")).isEqualTo("unreachable");
            assertThat(fields.containsKey("statusCode")).isFalse();
        }

        @Test
        void should_mark_failed_as_provider_no_response_when_gov_notify_did_not_respond() {
            final SocketTimeoutException socketTimeoutException = new SocketTimeoutException("Read timed out");
            final GovNotifyException noResponse = new GovNotifyException(
                    0, "java.net.SocketTimeoutException: Read timed out", socketTimeoutException);

            govNotifyFailureHandler.statusCheckFailed(
                    NOTIFICATION_ID, EMAIL, noResponse, jobWithRetriesRemaining(3), RETRY_DURATIONS_SECS);

            verify(statusService).markFailed(new NotificationFailure(
                    NOTIFICATION_ID, EMAIL, 0, "java.net.SocketTimeoutException: Read timed out",
                    PROVIDER_NO_RESPONSE));
        }
    }

    private static ILoggingEvent onlyEventIn(final CapturedLogs capturedLogs) {
        final List<ILoggingEvent> loggingEvents = capturedLogs.events();
        assertThat(loggingEvents).hasSize(1);
        return loggingEvents.getFirst();
    }

    private static Map<String, Object> fieldsOf(final ILoggingEvent loggingEvent) {
        final Map<String, Object> fields = new HashMap<>();
        final Optional<List<KeyValuePair>> possibleKeyValuePairs = ofNullable(loggingEvent.getKeyValuePairs());
        final List<KeyValuePair> keyValuePairs = possibleKeyValuePairs.orElse(List.of());
        for (final KeyValuePair keyValuePair : keyValuePairs) {
            fields.put(keyValuePair.key, keyValuePair.value);
        }
        return fields;
    }

    private static ExecutionInfo jobWithRetriesRemaining(final int retryAttemptsRemaining) {
        final JsonObject jobData = provider().createObjectBuilder()
                .add("notificationId", NOTIFICATION_ID.toString())
                .build();
        return executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CheckEmailStatusTask.TASK_NAME)
                .withAssignedTaskStartTime(ofInstant(NOW, UTC))
                .withExecutionStatus(STARTED)
                .withRetryAttemptsRemaining(retryAttemptsRemaining)
                .build();
    }
}
