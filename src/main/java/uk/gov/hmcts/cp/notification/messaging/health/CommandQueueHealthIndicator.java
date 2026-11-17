package uk.gov.hmcts.cp.notification.messaging.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Reachable;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.Duration;
import java.time.ZonedDateTime;

import static org.springframework.boot.health.contributor.Health.down;
import static org.springframework.boot.health.contributor.Health.up;

/**
 * Whether one command queue can be reached: UP only while its last peek succeeded, and recently.
 *
 * <p>It can't tell whether messages are being received: a stuck consumer on a reachable queue shows up in the
 * queue's depth and the age of its oldest message, which are monitored separately (PEG-3389).
 */
public class CommandQueueHealthIndicator implements HealthIndicator {
    private final QueueReachabilityCheck queueReachabilityCheck;
    private final Duration successfulPeekCountsFor;
    private final Clock clock;

    public CommandQueueHealthIndicator(
            final QueueReachabilityCheck queueReachabilityCheck,
            final Duration successfulPeekCountsFor,
            final Clock clock) {
        this.queueReachabilityCheck = queueReachabilityCheck;
        this.successfulPeekCountsFor = successfulPeekCountsFor;
        this.clock = clock;
    }

    @Override
    public Health health() {
        final Health queueHealth;
        if (queueReachabilityCheck.getLastCheck() instanceof Reachable reachable && wasReachedRecently(reachable)) {
            queueHealth = up().build();
        } else {
            queueHealth = down().build();
        }
        return queueHealth;
    }

    private boolean wasReachedRecently(final Reachable reachable) {
        final ZonedDateTime successfulPeekExpiresAt = reachable.reachedAt().plus(successfulPeekCountsFor);
        return successfulPeekExpiresAt.isAfter(clock.zonedDateTime());
    }
}
