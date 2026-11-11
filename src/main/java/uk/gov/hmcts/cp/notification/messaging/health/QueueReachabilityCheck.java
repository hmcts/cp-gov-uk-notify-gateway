package uk.gov.hmcts.cp.notification.messaging.health;

import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.slf4j.Logger;

import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.NotYetChecked;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Reachable;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Unreachable;

import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Peeks one queue and remembers whether it could be reached. The processor client doesn't say whether it is
 * connected (the SDK reconnects quietly), so this is how readiness knows. Peeking takes no lock and leaves any
 * message where it is.
 *
 * <p>Logs only when the queue becomes reachable or unreachable, not on every check.
 *
 * <p>Deliberately mutable: remembering the outcome of the last check is this class's job. The poller writes it
 * and the readiness probe reads it on different threads, so it is held in an {@link AtomicReference}.
 */
public class QueueReachabilityCheck implements AutoCloseable {
    private final String queueName;
    private final ServiceBusReceiverClient queuePeekClient;
    private final Logger logger;
    private final AtomicReference<QueueReachability> outcomeOfLastCheck = new AtomicReference<>(new NotYetChecked());

    public QueueReachabilityCheck(
            final String queueName, final ServiceBusReceiverClient queuePeekClient, final Logger logger) {
        this.queueName = queueName;
        this.queuePeekClient = queuePeekClient;
        this.logger = logger;
    }

    // Any failure means the queue can't be reached, not just an AzureException: a peek that runs out of time
    // throws Reactor's IllegalStateException ("Timeout on blocking read"), and letting that escape would stop the
    // other queues being checked and skip the warning below.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void checkAt(final ZonedDateTime checkedAt) {
        try {
            queuePeekClient.peekMessage();
            recordReachable(checkedAt);
        } catch (final RuntimeException peekFailure) {
            recordUnreachable(new Unreachable(checkedAt, peekFailure));
        }
    }

    public QueueReachability lastOutcome() {
        return outcomeOfLastCheck.get();
    }

    @Override
    public void close() {
        queuePeekClient.close();
    }

    private void recordReachable(final ZonedDateTime checkedAt) {
        final QueueReachability previousQueueReachability = outcomeOfLastCheck.getAndSet(new Reachable(checkedAt));
        if (!(previousQueueReachability instanceof Reachable)) {
            logger.info("Service Bus queue {} can be reached. This instance can read notification requests from it, "
                    + "so this queue no longer stops it reporting itself ready.", queueName);
        }
    }

    private void recordUnreachable(final Unreachable unreachableQueue) {
        final QueueReachability previousQueueReachability = outcomeOfLastCheck.getAndSet(unreachableQueue);
        if (!(previousQueueReachability instanceof Unreachable)) {
            logger.warn("Service Bus queue {} cannot be reached: {}. This instance reports itself not ready until "
                    + "the queue can be reached again. Check that the queue exists in the Service Bus namespace and "
                    + "that this service is allowed to read from it.",
                    queueName, unreachableQueue.peekFailureDescription());
        }
    }
}
