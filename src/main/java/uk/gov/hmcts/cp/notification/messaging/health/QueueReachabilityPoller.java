package uk.gov.hmcts.cp.notification.messaging.health;

import org.springframework.scheduling.annotation.Scheduled;

import uk.gov.hmcts.cp.notification.time.Clock;

import java.util.List;

import static java.util.List.copyOf;

/**
 * Runs the queue reachability checks on the scheduler thread: straight away once the application has started, so
 * readiness doesn't wait a whole interval, and then on a schedule. Checking in the background means a slow or
 * unreachable Service Bus never holds up startup or makes the readiness probe itself time out.
 */
public class QueueReachabilityPoller {
    private final List<QueueReachabilityCheck> queueReachabilityChecks;
    private final Clock clock;

    public QueueReachabilityPoller(final List<QueueReachabilityCheck> queueReachabilityChecks, final Clock clock) {
        this.queueReachabilityChecks = copyOf(queueReachabilityChecks);
        this.clock = clock;
    }

    // No initial delay: the first check runs as soon as scheduling starts.
    @Scheduled(fixedDelayString = "${cp.notification.servicebus.health-check.interval}")
    public void checkEveryQueue() {
        queueReachabilityChecks.forEach(
                queueReachabilityCheck -> queueReachabilityCheck.checkAt(clock.zonedDateTime()));
    }
}
