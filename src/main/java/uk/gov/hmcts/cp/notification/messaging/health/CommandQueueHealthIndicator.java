package uk.gov.hmcts.cp.notification.messaging.health;

import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.NotYetChecked;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Reachable;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Unreachable;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.Duration;
import java.time.ZonedDateTime;

import static org.springframework.boot.health.contributor.Health.down;
import static org.springframework.boot.health.contributor.Health.up;

/**
 * Whether one command queue can be consumed: UP only while its processor has been started and not closed, and the
 * queue was reached recently.
 *
 * <p>This can't tell whether messages are actually being received. The reachability check uses its own connection,
 * and the processor reports itself running from start until close, even while the SDK is recovering from errors.
 * A processor that is stuck while the queue can be reached shows up in the queue's depth and the age of its oldest
 * message, which are monitored separately (PEG-3389).
 */
public class CommandQueueHealthIndicator implements HealthIndicator {
    private static final String REASON = "reason";
    private static final String CHECKED_AT = "checkedAt";

    private final ServiceBusProcessorClient commandQueueProcessorClient;
    private final QueueReachabilityCheck queueReachabilityCheck;
    private final Clock clock;
    private final Duration reachabilityStaleAfter;

    public CommandQueueHealthIndicator(
            final ServiceBusProcessorClient commandQueueProcessorClient,
            final QueueReachabilityCheck queueReachabilityCheck,
            final Clock clock,
            final Duration reachabilityStaleAfter) {
        this.commandQueueProcessorClient = commandQueueProcessorClient;
        this.queueReachabilityCheck = queueReachabilityCheck;
        this.clock = clock;
        this.reachabilityStaleAfter = reachabilityStaleAfter;
    }

    @Override
    public Health health() {
        final Health commandQueueHealth;
        if (commandQueueProcessorClient.isRunning()) {
            commandQueueHealth = healthFor(queueReachabilityCheck.lastOutcome(), clock.zonedDateTime());
        } else {
            commandQueueHealth = down().withDetail(REASON, "The queue's processor is not running").build();
        }
        return commandQueueHealth;
    }

    private Health healthFor(final QueueReachability queueReachability, final ZonedDateTime currentTime) {
        return switch (queueReachability) {
            case NotYetChecked _ -> down()
                    .withDetail(REASON, "The queue has not been checked yet")
                    .build();
            case Unreachable unreachableQueue -> down()
                    .withDetail(REASON, "The queue could not be reached")
                    .withDetail(CHECKED_AT, unreachableQueue.checkedAt().toString())
                    .withDetail("error", unreachableQueue.peekFailureDescription())
                    .build();
            case Reachable reachableQueue
                    when reachableQueue.checkedAt().plus(reachabilityStaleAfter).isBefore(currentTime) -> down()
                    .withDetail(REASON, "The queue has not been reached since " + reachableQueue.checkedAt())
                    .withDetail(CHECKED_AT, reachableQueue.checkedAt().toString())
                    .build();
            case Reachable reachableQueue -> up()
                    .withDetail(CHECKED_AT, reachableQueue.checkedAt().toString())
                    .build();
        };
    }
}
