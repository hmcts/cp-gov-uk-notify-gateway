package uk.gov.hmcts.cp.notification.messaging.health;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Timings for the Service Bus readiness check, checked at startup so a bad combination can't make readiness flap.
 *
 * @param interval  how often each command queue is peeked to confirm it can still be reached
 * @param timeout   how long one peek may take before the queue counts as unreachable
 * @param staleAfter how old the last successful peek may be before the queue counts as unreachable, so a stalled
 *                   check can't leave the service looking healthy
 */
@ConfigurationProperties(prefix = "cp.notification.servicebus.health-check")
public record ServiceBusHealthCheckProperties(
        @DefaultValue("30s") Duration interval,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("90s") Duration staleAfter) {

    public ServiceBusHealthCheckProperties {
        if (!interval.isPositive() || !timeout.isPositive()) {
            throw new IllegalArgumentException(("The Service Bus health check interval (%s) and timeout (%s) must "
                    + "both be longer than zero. Check CP_NG_SERVICEBUS_HEALTH_CHECK_INTERVAL and "
                    + "CP_NG_SERVICEBUS_HEALTH_CHECK_TIMEOUT.").formatted(interval, timeout));
        }
        if (staleAfter.compareTo(interval.plus(timeout)) <= 0) {
            throw new IllegalArgumentException(("The Service Bus health check stale-after (%s) must be longer than "
                    + "the interval (%s) plus the timeout (%s), or readiness would fail between checks while the "
                    + "queues are fine. Check CP_NG_SERVICEBUS_HEALTH_CHECK_STALE_AFTER.")
                    .formatted(staleAfter, interval, timeout));
        }
    }
}
