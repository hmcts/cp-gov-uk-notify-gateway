package uk.gov.hmcts.cp.notification.integration.base;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.cp.notification.integration.config.MaterialSasTestConfig;
import uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub;
import uk.gov.hmcts.cp.notification.integration.stubs.Office365StubService;
import uk.gov.hmcts.cp.notification.integration.stubs.support.AzuriteContainerSupport;
import uk.gov.hmcts.cp.notification.integration.stubs.GovUkNotifyStubService;
import uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport;

@AutoConfigureTestRestTemplate
@Import(MaterialSasTestConfig.class)
public abstract class AbstractEndToEndIntegrationTest extends AbstractIntegrationTest {
    @DynamicPropertySource
    static void endToEndProperties(final DynamicPropertyRegistry registry) {
        ServiceBusContainerSupport.registerProperties(registry);
        AzuriteContainerSupport.registerProperties(registry);
        GovUkNotifyStubService.registerProperties(registry);
        MaterialServiceStub.registerProperties(registry);
        Office365StubService.registerProperties(registry);
    }
}
