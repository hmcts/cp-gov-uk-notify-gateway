package uk.gov.hmcts.cp.notification.messaging;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.boot.health.contributor.Status.DOWN;
import static org.springframework.boot.health.contributor.Status.UP;

import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

class ServiceBusHealthIndicatorTest {
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private final ServiceBusReceiverClient emailQueuePeekClient = mock(ServiceBusReceiverClient.class);
    private final ServiceBusReceiverClient letterQueuePeekClient = mock(ServiceBusReceiverClient.class);
    private final Clock clock = mock(Clock.class);
    private ServiceBusHealthIndicator serviceBusHealthIndicator;

    @BeforeEach
    void createIndicator() {
        final Map<String, ServiceBusReceiverClient> peekClients = new LinkedHashMap<>();
        peekClients.put("ng-send-email", emailQueuePeekClient);
        peekClients.put("ng-send-letter", letterQueuePeekClient);
        serviceBusHealthIndicator = new ServiceBusHealthIndicator(peekClients, ofSeconds(100), clock);
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void should_be_down_before_any_queue_has_been_peeked() {
        final Health health = serviceBusHealthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(DOWN);
        assertThat(health.getDetails()).containsEntry(
                "queues", Map.of("ng-send-email", "unreachable", "ng-send-letter", "unreachable"));
    }

    @Test
    void should_be_up_when_every_queue_was_peeked_recently() {
        serviceBusHealthIndicator.peekEveryQueue();

        final Health health = serviceBusHealthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(UP);
        assertThat(health.getDetails()).containsEntry(
                "queues", Map.of("ng-send-email", "reachable", "ng-send-letter", "reachable"));
    }

    @Test
    void should_be_down_when_one_queue_cannot_be_peeked() {
        when(letterQueuePeekClient.peekMessage()).thenThrow(new IllegalStateException("Timeout on blocking read"));

        serviceBusHealthIndicator.peekEveryQueue();
        final Health health = serviceBusHealthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(DOWN);
        assertThat(health.getDetails()).containsEntry(
                "queues", Map.of("ng-send-email", "reachable", "ng-send-letter", "unreachable"));
    }

    @Test
    void should_be_down_when_the_last_successful_peek_is_too_old() {
        serviceBusHealthIndicator.peekEveryQueue();
        when(clock.instant()).thenReturn(NOW.plusSeconds(101));

        assertThat(serviceBusHealthIndicator.health().getStatus()).isEqualTo(DOWN);
    }

    @Test
    void should_be_up_again_once_a_failing_queue_can_be_peeked() {
        when(letterQueuePeekClient.peekMessage())
                .thenThrow(new IllegalStateException("Timeout on blocking read"))
                .thenReturn(null);

        serviceBusHealthIndicator.peekEveryQueue();
        serviceBusHealthIndicator.peekEveryQueue();

        assertThat(serviceBusHealthIndicator.health().getStatus()).isEqualTo(UP);
    }

    @Test
    void should_close_every_peek_client() {
        serviceBusHealthIndicator.close();

        verify(emailQueuePeekClient).close();
        verify(letterQueuePeekClient).close();
    }
}
