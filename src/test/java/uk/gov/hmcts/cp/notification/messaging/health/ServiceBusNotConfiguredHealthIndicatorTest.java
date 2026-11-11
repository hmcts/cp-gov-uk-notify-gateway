package uk.gov.hmcts.cp.notification.messaging.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.health.contributor.Status.UNKNOWN;

class ServiceBusNotConfiguredHealthIndicatorTest {

    @Test
    void should_report_unknown_because_there_is_nothing_to_consume() {
        final Health serviceBusHealth = new ServiceBusNotConfiguredHealthIndicator().health();

        assertThat(serviceBusHealth.getStatus()).isEqualTo(UNKNOWN);
        assertThat(serviceBusHealth.getDetails()).containsEntry("reason", "Service Bus is not configured");
    }
}
