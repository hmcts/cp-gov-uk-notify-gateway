package uk.gov.hmcts.cp.notification.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ch.qos.logback.classic.Level.INFO;
import static ch.qos.logback.classic.Level.WARN;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.PERMANENT_FAILURE;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.SUCCEEDED;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MICROSOFT_365;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;

class DependencyCallLoggerTest {
    private final DependencyCallLogger dependencyCallLogger = new DependencyCallLogger();

    @Test
    void warn_should_log_the_message_at_warn_with_every_field_of_the_dependency_call() {
        final DependencyCall dependencyCall = new DependencyCall(MATERIAL, DOWNLOAD, LETTER, PERMANENT_FAILURE, of(404));

        final ILoggingEvent loggingEvent;
        try (CapturedLogs capturedLogs = captureLogsOf(DependencyCallLogger.class)) {
            dependencyCallLogger.warn(dependencyCall, "Material download failed for notification 'n-1'");
            loggingEvent = onlyEventIn(capturedLogs);
        }

        assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
        assertThat(loggingEvent.getFormattedMessage()).isEqualTo("Material download failed for notification 'n-1'");
        final Map<String, Object> fields = fieldsOf(loggingEvent);
        assertThat(fields).hasSize(5);
        assertThat(fields.get("dependency")).isEqualTo("material");
        assertThat(fields.get("operation")).isEqualTo("download");
        assertThat(fields.get("channel")).isEqualTo("letter");
        assertThat(fields.get("outcome")).isEqualTo("permanent-failure");
        assertThat(fields.get("statusCode")).isEqualTo(404);
    }

    @Test
    void info_should_log_the_message_at_info_without_a_status_code_field_when_there_is_no_status_code() {
        final DependencyCall dependencyCall = new DependencyCall(MICROSOFT_365, SEND, EMAIL, SUCCEEDED, empty());

        final ILoggingEvent loggingEvent;
        try (CapturedLogs capturedLogs = captureLogsOf(DependencyCallLogger.class)) {
            dependencyCallLogger.info(dependencyCall, "Sent notification 'n-2' via 'Office365Client'");
            loggingEvent = onlyEventIn(capturedLogs);
        }

        assertThat(loggingEvent.getLevel()).isEqualTo(INFO);
        assertThat(loggingEvent.getFormattedMessage()).isEqualTo("Sent notification 'n-2' via 'Office365Client'");
        final Map<String, Object> fields = fieldsOf(loggingEvent);
        assertThat(fields).hasSize(4);
        assertThat(fields.get("dependency")).isEqualTo("microsoft-365");
        assertThat(fields.get("operation")).isEqualTo("send");
        assertThat(fields.get("channel")).isEqualTo("email");
        assertThat(fields.get("outcome")).isEqualTo("succeeded");
        assertThat(fields.containsKey("statusCode")).isFalse();
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
