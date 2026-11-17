package uk.gov.hmcts.cp.notification.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

import static uk.gov.hmcts.cp.notification.logging.Redaction.redacted;

/**
 * Service Bus settings as bound from configuration. The connection string is a credential: it is the emulator /
 * local exception (NFR-002), and without one the clients authenticate to the namespace (NG-S07).
 */
@ConfigurationProperties(prefix = "cp.notification.servicebus")
public record ServiceBusProperties(
        @DefaultValue("") String connectionString,
        @DefaultValue("") String namespace,
        @DefaultValue("ng-send-email") String commandQueue,
        @DefaultValue("ng-send-letter") String letterCommandQueue,
        @DefaultValue("15") int maxConcurrentCalls,
        @DefaultValue HealthCheck healthCheck) {

    @Override
    public String toString() {
        return """
                ServiceBusProperties[\
                connectionString=%s, \
                namespace=%s, \
                commandQueue=%s, \
                letterCommandQueue=%s, \
                maxConcurrentCalls=%s, \
                healthCheck=%s]"""
                .formatted(
                        redacted(connectionString),
                        namespace,
                        commandQueue,
                        letterCommandQueue,
                        maxConcurrentCalls,
                        healthCheck);
    }

    /**
     * How often readiness peeks each command queue, and how long one peek may take. Checked at startup.
     */
    public record HealthCheck(
            @DefaultValue("30s") Duration interval,
            @DefaultValue("10s") Duration timeout) {

        public HealthCheck {
            if (!interval.isPositive() || !timeout.isPositive()) {
                throw new IllegalArgumentException("""
                        The Service Bus health check interval (%s) and timeout (%s) \
                        must both be longer than zero. \
                        Check CP_NG_SERVICEBUS_HEALTH_CHECK_INTERVAL \
                        and CP_NG_SERVICEBUS_HEALTH_CHECK_TIMEOUT."""
                        .formatted(interval, timeout));
            }
        }
    }
}
