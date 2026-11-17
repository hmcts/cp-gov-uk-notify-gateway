package uk.gov.hmcts.cp.notification.messaging.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.messaging.ConditionalOnServiceBusNotConfigured;

import static org.springframework.boot.health.contributor.Health.unknown;
import static uk.gov.hmcts.cp.notification.messaging.health.ServiceBusHealthConfig.SERVICE_BUS_HEALTH;

/**
 * With no Service Bus configured (local runs and tests that don't need it) there is nothing to consume, so Service
 * Bus reports UNKNOWN, which doesn't fail readiness.
 */
@Component(SERVICE_BUS_HEALTH)
@ConditionalOnServiceBusNotConfigured
public class ServiceBusNotConfiguredHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        return unknown().build();
    }
}
