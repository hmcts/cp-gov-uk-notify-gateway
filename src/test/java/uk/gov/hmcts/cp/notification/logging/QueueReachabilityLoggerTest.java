package uk.gov.hmcts.cp.notification.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ch.qos.logback.classic.Level.INFO;
import static ch.qos.logback.classic.Level.WARN;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;

class QueueReachabilityLoggerTest {
    private final QueueReachabilityLogger queueReachabilityLogger = new QueueReachabilityLogger();

    @Test
    void reachable_should_tell_an_operator_at_info_that_the_queue_no_longer_stops_readiness() {
        final List<ILoggingEvent> loggingEvents;
        try (CapturedLogs capturedLogs = captureLogsOf(QueueReachabilityLogger.class)) {
            queueReachabilityLogger.reachable("ng-send-email");
            loggingEvents = capturedLogs.events();
        }

        assertThat(loggingEvents).hasSize(1);
        final ILoggingEvent loggingEvent = loggingEvents.getFirst();
        assertThat(loggingEvent.getLevel()).isEqualTo(INFO);
        assertThat(loggingEvent.getFormattedMessage()).isEqualTo("""
                Service Bus queue 'ng-send-email' can be reached. \
                This instance can read notification requests from it, \
                so this queue no longer stops it reporting itself ready.""");
    }

    @Test
    void unreachable_should_warn_an_operator_with_the_peek_failure_and_what_to_check() {
        final List<ILoggingEvent> loggingEvents;
        try (CapturedLogs capturedLogs = captureLogsOf(QueueReachabilityLogger.class)) {
            queueReachabilityLogger.unreachable("ng-send-letter", new IllegalStateException("Timeout on blocking read"));
            loggingEvents = capturedLogs.events();
        }

        assertThat(loggingEvents).hasSize(1);
        final ILoggingEvent loggingEvent = loggingEvents.getFirst();
        assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
        assertThat(loggingEvent.getFormattedMessage()).isEqualTo("""
                Service Bus queue 'ng-send-letter' cannot be reached: 'Timeout on blocking read'. \
                This instance reports itself not ready until the queue can be reached again. \
                Check that the queue exists in the Service Bus namespace \
                and that this service is allowed to read from it.""");
    }

    @Test
    void unreachable_should_name_the_failure_type_when_the_failure_has_no_message() {
        final List<ILoggingEvent> loggingEvents;
        try (CapturedLogs capturedLogs = captureLogsOf(QueueReachabilityLogger.class)) {
            queueReachabilityLogger.unreachable("ng-send-letter", new IllegalStateException());
            loggingEvents = capturedLogs.events();
        }

        final ILoggingEvent loggingEvent = loggingEvents.getFirst();
        assertThat(loggingEvent.getFormattedMessage())
                .startsWith("Service Bus queue 'ng-send-letter' cannot be reached: 'java.lang.IllegalStateException'.");
    }
}
