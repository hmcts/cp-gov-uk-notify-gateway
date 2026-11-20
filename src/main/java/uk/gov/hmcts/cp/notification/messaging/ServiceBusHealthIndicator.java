package uk.gov.hmcts.cp.notification.messaging;

import static org.slf4j.LoggerFactory.getLogger;

import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.slf4j.Logger;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;

import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "serviceBus" readiness component (decision log D6): UP only while every command queue was peeked successfully
 * within {@code reachedCountsFor}. Peeking takes no lock and leaves any message where it is.
 *
 * <p>The queues are peeked in the background, not when the probe calls, so a slow or unreachable Service Bus never
 * holds up startup or makes the probe itself time out. A stuck consumer on a reachable queue doesn't show here; it
 * shows in the queue's depth and the age of its oldest message, which are monitored separately (PEG-3389).
 */
public class ServiceBusHealthIndicator implements HealthIndicator, AutoCloseable {
    public static final String NAME = "serviceBus";

    private static final Logger LOG = getLogger(ServiceBusHealthIndicator.class);

    private final Map<String, ServiceBusReceiverClient> peekClients;
    private final Duration reachedCountsFor;
    private final Clock clock;
    private final Map<String, Instant> lastReachedAt = new ConcurrentHashMap<>();
    private final Map<String, Boolean> lastPeekSucceeded = new ConcurrentHashMap<>();

    public ServiceBusHealthIndicator(
            final Map<String, ServiceBusReceiverClient> peekClients,
            final Duration reachedCountsFor,
            final Clock clock) {
        this.peekClients = new LinkedHashMap<>(peekClients);
        this.reachedCountsFor = reachedCountsFor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cp.notification.servicebus.health-check.interval:30s}")
    public void peekEveryQueue() {
        peekClients.forEach(this::peek);
    }

    @Override
    public Health health() {
        final Instant reachedAfter = clock.instant().minus(reachedCountsFor);
        final Map<String, String> queues = new LinkedHashMap<>();
        peekClients.keySet().forEach(queue -> {
            final Instant reachedAt = lastReachedAt.get(queue);
            queues.put(queue, reachedAt != null && reachedAt.isAfter(reachedAfter) ? "reachable" : "unreachable");
        });
        final Health.Builder health = queues.containsValue("unreachable") ? Health.down() : Health.up();
        return health.withDetail("queues", queues).build();
    }

    @Override
    public void close() {
        peekClients.values().forEach(ServiceBusReceiverClient::close);
    }

    // A peek that runs out of time throws Reactor's IllegalStateException, not an AzureException.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void peek(final String queue, final ServiceBusReceiverClient peekClient) {
        try {
            peekClient.peekMessage();
            lastReachedAt.put(queue, clock.instant());
            if (!Boolean.TRUE.equals(lastPeekSucceeded.put(queue, true))) {
                LOG.info("Service Bus queue '{}' can be reached, so it no longer stops this instance being ready.",
                        queue);
            }
        } catch (final RuntimeException peekFailure) {
            if (!Boolean.FALSE.equals(lastPeekSucceeded.put(queue, false))) {
                LOG.warn("Service Bus queue '{}' cannot be reached: '{}'. This instance reports itself not ready "
                                + "until it can. Check that the queue exists and that this service may read from it.",
                        queue, peekFailure.getMessage());
            }
        }
    }
}
