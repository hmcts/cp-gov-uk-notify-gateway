package uk.gov.hmcts.cp.notification.integration.base;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.cp.notification.integration.stubs.support.AzuriteContainerSupport;
import uk.gov.hmcts.cp.notification.integration.stubs.GovUkNotifyStubService;
import uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport;
import uk.gov.hmcts.cp.notification.integration.stubs.support.WireMockSupport;

// The single full-application context: shared by the BDD acceptance suite and the wiring/sanity test
// (identical config, no mocks) so Spring caches one context for both. The job poll-interval comes from
// application-test.yaml. The ASB consumer boundary test runs on a separate queue, so this context's
// processor/poller never compete with it — hence no @DirtiesContext.
@AutoConfigureTestRestTemplate
public abstract class AbstractEndToEndIntegrationTest extends AbstractIntegrationTest {
    @DynamicPropertySource
    static void endToEndProperties(final DynamicPropertyRegistry registry) {
        ServiceBusContainerSupport.registerProperties(registry);
        AzuriteContainerSupport.registerProperties(registry);
        GovUkNotifyStubService.registerProperties(registry);
        registry.add("cp.notification.material.base-url", () -> WireMockSupport.baseUrl() + "/material-service");
    }
}
