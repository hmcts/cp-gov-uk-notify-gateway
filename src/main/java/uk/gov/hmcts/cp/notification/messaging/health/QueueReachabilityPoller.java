package uk.gov.hmcts.cp.notification.messaging.health;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static java.util.List.copyOf;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Checks every command queue in the background: straight away, then again after each interval. So a slow or
 * unreachable Service Bus never holds up startup or makes the readiness probe itself time out.
 */
public class QueueReachabilityPoller implements AutoCloseable {
    private final List<QueueReachabilityCheck> queueReachabilityChecks;
    private final ScheduledExecutorService checkScheduler;
    private final Duration interval;

    public QueueReachabilityPoller(
            final List<QueueReachabilityCheck> queueReachabilityChecks,
            final ScheduledExecutorService checkScheduler,
            final Duration interval) {
        this.queueReachabilityChecks = copyOf(queueReachabilityChecks);
        this.checkScheduler = checkScheduler;
        this.interval = interval;
    }

    public List<QueueReachabilityCheck> getQueueReachabilityChecks() {
        return queueReachabilityChecks;
    }

    public void start() {
        checkScheduler.scheduleWithFixedDelay(this::checkEveryQueue, 0, interval.toMillis(), MILLISECONDS);
    }

    @Override
    public void close() {
        checkScheduler.close();
        queueReachabilityChecks.forEach(QueueReachabilityCheck::close);
    }

    private void checkEveryQueue() {
        queueReachabilityChecks.forEach(QueueReachabilityCheck::checkReachability);
    }
}
