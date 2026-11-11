package uk.gov.hmcts.cp.notification.messaging.health;

import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;

import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.NotYetChecked;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Reachable;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Unreachable;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.Duration;
import java.time.ZonedDateTime;

import static java.time.Duration.ofSeconds;
import static java.time.ZonedDateTime.parse;
import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.boot.health.contributor.Status.DOWN;
import static org.springframework.boot.health.contributor.Status.UP;

@ExtendWith(MockitoExtension.class)
class CommandQueueHealthIndicatorTest {
    private final Duration reachabilityStaleAfter = ofSeconds(90);
    private final ZonedDateTime checkedAt = parse("2026-11-10T10:00:00Z");

    @Mock
    private ServiceBusProcessorClient commandQueueProcessorClient;

    @Mock
    private QueueReachabilityCheck queueReachabilityCheck;

    @Mock
    private Clock clock;

    private CommandQueueHealthIndicator commandQueueHealthIndicator;

    @BeforeEach
    void createCommandQueueHealthIndicator() {
        commandQueueHealthIndicator = new CommandQueueHealthIndicator(
                commandQueueProcessorClient, queueReachabilityCheck, clock, reachabilityStaleAfter);
    }

    @Test
    void should_be_down_when_the_processor_is_not_running_whatever_the_queue_reachability() {
        when(commandQueueProcessorClient.isRunning()).thenReturn(false);

        final Health commandQueueHealth = commandQueueHealthIndicator.health();

        assertThat(commandQueueHealth.getStatus()).isEqualTo(DOWN);
        assertThat(commandQueueHealth.getDetails())
                .containsExactlyEntriesOf(of("reason", "The queue's processor is not running"));
        verifyNoInteractions(queueReachabilityCheck);
    }

    @Nested
    class WhenTheProcessorIsRunning {

        @BeforeEach
        void runTheProcessor() {
            when(commandQueueProcessorClient.isRunning()).thenReturn(true);
        }

        @Test
        void should_be_down_until_the_queue_has_been_checked() {
            when(queueReachabilityCheck.lastOutcome()).thenReturn(new NotYetChecked());

            final Health commandQueueHealth = commandQueueHealthIndicator.health();

            assertThat(commandQueueHealth.getStatus()).isEqualTo(DOWN);
            assertThat(commandQueueHealth.getDetails()).containsEntry("reason", "The queue has not been checked yet");
        }

        @Test
        void should_be_down_with_the_error_when_the_queue_could_not_be_reached() {
            final ServiceBusException peekFailure = new ServiceBusException(
                    new IllegalStateException("connection refused"), ServiceBusErrorSource.RECEIVE);
            when(queueReachabilityCheck.lastOutcome()).thenReturn(new Unreachable(checkedAt, peekFailure));

            final Health commandQueueHealth = commandQueueHealthIndicator.health();

            assertThat(commandQueueHealth.getStatus()).isEqualTo(DOWN);
            assertThat(commandQueueHealth.getDetails())
                    .containsEntry("reason", "The queue could not be reached")
                    .containsEntry("checkedAt", checkedAt.toString())
                    .containsEntry("error", peekFailure.getMessage());
        }

        @Test
        void should_be_up_when_the_queue_was_reached_within_the_stale_limit() {
            when(queueReachabilityCheck.lastOutcome()).thenReturn(new Reachable(checkedAt));
            when(clock.zonedDateTime()).thenReturn(checkedAt.plus(reachabilityStaleAfter));

            final Health commandQueueHealth = commandQueueHealthIndicator.health();

            assertThat(commandQueueHealth.getStatus()).isEqualTo(UP);
            assertThat(commandQueueHealth.getDetails()).containsEntry("checkedAt", checkedAt.toString());
        }

        @Test
        void should_be_down_when_the_queue_was_last_reached_before_the_stale_limit() {
            when(queueReachabilityCheck.lastOutcome()).thenReturn(new Reachable(checkedAt));
            when(clock.zonedDateTime()).thenReturn(checkedAt.plus(reachabilityStaleAfter).plusSeconds(1));

            final Health commandQueueHealth = commandQueueHealthIndicator.health();

            assertThat(commandQueueHealth.getStatus()).isEqualTo(DOWN);
            assertThat(commandQueueHealth.getDetails())
                    .containsEntry("reason", "The queue has not been reached since " + checkedAt)
                    .containsEntry("checkedAt", checkedAt.toString());
        }
    }
}
