package uk.gov.hmcts.cp.notification.messaging.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.messaging.ConditionalOnServiceBusNotConfigured;

import static org.springframework.boot.health.contributor.Health.unknown;

/**
 * With no Service Bus configured (local runs and tests that don't need it) there is nothing to consume, so Service
 * Bus reports UNKNOWN. The readiness group still names it, and UNKNOWN doesn't fail the probe.
 *
 * <p>Registered under the same name as the configured contributor, so the health component is "serviceBus" either way.
 */
@Component("serviceBusHealthIndicator")
@ConditionalOnServiceBusNotConfigured
public class ServiceBusNotConfiguredHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        return unknown().withDetail("reason", "Service Bus is not configured").build();
    }
}
