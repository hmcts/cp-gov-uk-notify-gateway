package uk.gov.hmcts.cp.notification.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ch.qos.logback.classic.Level.WARN;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.NotificationOutcome.FAILED;
import static uk.gov.hmcts.cp.notification.logging.NotificationOutcome.REJECTED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_UNREACHABLE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.UNREADABLE_REQUEST;

class NotificationOutcomeLoggerTest {
    private final NotificationOutcomeLogger notificationOutcomeLogger = new NotificationOutcomeLogger();

    @Test
    void warn_should_log_the_message_at_warn_with_every_field_of_a_rejection() {
        final NotificationOutcomeEvent notificationOutcomeEvent =
                new NotificationOutcomeEvent(REJECTED, UNREADABLE_REQUEST, LETTER, "dvla-notification-result");

        final ILoggingEvent loggingEvent;
        try (CapturedLogs capturedLogs = captureLogsOf(NotificationOutcomeLogger.class)) {
            notificationOutcomeLogger.warn(notificationOutcomeEvent,
                    "Rejected the letter request for reply queue 'dvla-notification-result'");
            loggingEvent = onlyEventIn(capturedLogs);
        }

        assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
        assertThat(loggingEvent.getFormattedMessage())
                .isEqualTo("Rejected the letter request for reply queue 'dvla-notification-result'");
        final Map<String, Object> fields = fieldsOf(loggingEvent);
        assertThat(fields).hasSize(4);
        assertThat(fields.get("replyQueue")).isEqualTo("dvla-notification-result");
        assertThat(fields.get("notificationOutcome")).isEqualTo("rejected");
        assertThat(fields.get("outcomeReason")).isEqualTo("unreadable-request");
        assertThat(fields.get("notificationChannel")).isEqualTo("letter");
    }

    @Test
    void warn_should_log_every_field_of_a_failure() {
        final NotificationOutcomeEvent notificationOutcomeEvent =
                new NotificationOutcomeEvent(FAILED, PROVIDER_UNREACHABLE, EMAIL, "sjp-notification-result");

        final ILoggingEvent loggingEvent;
        try (CapturedLogs capturedLogs = captureLogsOf(NotificationOutcomeLogger.class)) {
            notificationOutcomeLogger.warn(notificationOutcomeEvent, "Notification 'n-1' failed");
            loggingEvent = onlyEventIn(capturedLogs);
        }

        assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
        assertThat(loggingEvent.getFormattedMessage()).isEqualTo("Notification 'n-1' failed");
        final Map<String, Object> fields = fieldsOf(loggingEvent);
        assertThat(fields).hasSize(4);
        assertThat(fields.get("replyQueue")).isEqualTo("sjp-notification-result");
        assertThat(fields.get("notificationOutcome")).isEqualTo("failed");
        assertThat(fields.get("outcomeReason")).isEqualTo("provider-unreachable");
        assertThat(fields.get("notificationChannel")).isEqualTo("email");
    }

    private ILoggingEvent onlyEventIn(final CapturedLogs capturedLogs) {
        final List<ILoggingEvent> loggingEvents = capturedLogs.events();
        assertThat(loggingEvents).hasSize(1);
        return loggingEvents.getFirst();
    }

    private Map<String, Object> fieldsOf(final ILoggingEvent loggingEvent) {
        final Map<String, Object> fields = new HashMap<>();
        final List<KeyValuePair> keyValuePairs = loggingEvent.getKeyValuePairs();
        for (final KeyValuePair keyValuePair : keyValuePairs) {
            fields.put(keyValuePair.key, keyValuePair.value);
        }
        return fields;
    }
}
