package uk.gov.hmcts.cp.notification.messaging.health;

import com.azure.messaging.servicebus.ServiceBusReceiverClient;

import uk.gov.hmcts.cp.notification.logging.QueueReachabilityLogger;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.NotYetChecked;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Reachable;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Unreachable;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Peeks one command queue and remembers whether it could be reached. Peeking takes no lock and leaves any message
 * where it is.
 */
public class QueueReachabilityCheck implements AutoCloseable {
    private final String queueName;
    private final ServiceBusReceiverClient queuePeekClient;
    private final QueueReachabilityLogger queueReachabilityLogger;
    private final Clock clock;
    private final AtomicReference<QueueReachability> lastCheck = new AtomicReference<>(new NotYetChecked());

    public QueueReachabilityCheck(
            final String queueName,
            final ServiceBusReceiverClient queuePeekClient,
            final QueueReachabilityLogger queueReachabilityLogger,
            final Clock clock) {
        this.queueName = queueName;
        this.queuePeekClient = queuePeekClient;
        this.queueReachabilityLogger = queueReachabilityLogger;
        this.clock = clock;
    }

    public String getQueueName() {
        return queueName;
    }

    public QueueReachability getLastCheck() {
        return lastCheck.get();
    }

    // A peek that runs out of time throws Reactor's IllegalStateException, not an AzureException.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void checkReachability() {
        try {
            queuePeekClient.peekMessage();
            recordReachable();
        } catch (final RuntimeException peekFailure) {
            recordUnreachable(peekFailure);
        }
    }

    @Override
    public void close() {
        queuePeekClient.close();
    }

    private void recordReachable() {
        final QueueReachability previousCheck = lastCheck.getAndSet(new Reachable(clock.zonedDateTime()));
        if (!(previousCheck instanceof Reachable)) {
            queueReachabilityLogger.reachable(queueName);
        }
    }

    private void recordUnreachable(final RuntimeException peekFailure) {
        final QueueReachability previousCheck = lastCheck.getAndSet(new Unreachable());
        if (!(previousCheck instanceof Unreachable)) {
            queueReachabilityLogger.unreachable(queueName, peekFailure);
        }
    }
}
