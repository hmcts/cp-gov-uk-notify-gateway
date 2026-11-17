package uk.gov.hmcts.cp.notification.messaging.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.NotYetChecked;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Reachable;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Unreachable;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.Duration;
import java.time.ZonedDateTime;

import static java.time.Duration.ofSeconds;
import static java.time.ZonedDateTime.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.boot.health.contributor.Status.DOWN;
import static org.springframework.boot.health.contributor.Status.UP;

@ExtendWith(MockitoExtension.class)
class CommandQueueHealthIndicatorTest {
    private final Duration successfulPeekCountsFor = ofSeconds(100);
    private final ZonedDateTime reachedAt = parse("2026-11-10T10:00:00Z");

    @Mock
    private QueueReachabilityCheck queueReachabilityCheck;

    @Mock
    private Clock clock;

    private CommandQueueHealthIndicator commandQueueHealthIndicator;

    @BeforeEach
    void createHealthIndicatorOverTheMockedCheck() {
        commandQueueHealthIndicator =
                new CommandQueueHealthIndicator(queueReachabilityCheck, successfulPeekCountsFor, clock);
    }

    @Test
    void should_be_down_before_the_queue_has_been_checked() {
        when(queueReachabilityCheck.getLastCheck()).thenReturn(new NotYetChecked());

        assertThat(commandQueueHealthIndicator.health().getStatus()).isEqualTo(DOWN);
    }

    @Test
    void should_be_down_when_the_queue_could_not_be_reached() {
        when(queueReachabilityCheck.getLastCheck()).thenReturn(new Unreachable());

        assertThat(commandQueueHealthIndicator.health().getStatus()).isEqualTo(DOWN);
    }

    @Test
    void should_be_up_while_the_successful_peek_still_counts() {
        when(queueReachabilityCheck.getLastCheck()).thenReturn(new Reachable(reachedAt));
        when(clock.zonedDateTime()).thenReturn(reachedAt.plus(successfulPeekCountsFor).minusSeconds(1));

        assertThat(commandQueueHealthIndicator.health().getStatus()).isEqualTo(UP);
    }

    @Test
    void should_be_down_once_the_successful_peek_no_longer_counts() {
        when(queueReachabilityCheck.getLastCheck()).thenReturn(new Reachable(reachedAt));
        when(clock.zonedDateTime()).thenReturn(reachedAt.plus(successfulPeekCountsFor));

        assertThat(commandQueueHealthIndicator.health().getStatus()).isEqualTo(DOWN);
    }
}
