package uk.gov.hmcts.cp.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import uk.gov.hmcts.cp.notification.integration.base.AbstractIntegrationTest;
import uk.gov.hmcts.cp.notification.integration.stubs.GovUkNotifyStubService;
import uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub;
import uk.gov.hmcts.cp.notification.integration.stubs.Office365StubService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.health.contributor.Status.UNKNOWN;
import static org.springframework.boot.health.contributor.Status.UP;

/**
 * The whole application with no Service Bus configured, as in a local run. Readiness names the "serviceBus"
 * component, and Boot refuses to start if a readiness member doesn't exist, so this proves the stand-in
 * component is registered under that name.
 */
// Closed afterwards, so this application's job poller can't pick up work from the end-to-end tests' database.
@DirtiesContext
class ServiceBusNotConfiguredIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private HealthEndpoint healthEndpoint;

    // The providers the application can't start without; Service Bus is deliberately left out.
    @DynamicPropertySource
    static void providerProperties(final DynamicPropertyRegistry registry) {
        GovUkNotifyStubService.registerProperties(registry);
        Office365StubService.registerProperties(registry);
        MaterialServiceStub.registerProperties(registry);
    }

    @Test
    void should_start_and_report_service_bus_as_unknown_without_failing_readiness() {
        final CompositeHealthDescriptor readinessHealth =
                (CompositeHealthDescriptor) healthEndpoint.healthForPath("readiness");

        assertThat(readinessHealth.getStatus()).isEqualTo(UP);
        assertThat(readinessHealth.getComponents()).containsOnlyKeys("readinessState", "db", "serviceBus");
        assertThat(readinessHealth.getComponents().get("serviceBus").getStatus()).isEqualTo(UNKNOWN);
    }
}
