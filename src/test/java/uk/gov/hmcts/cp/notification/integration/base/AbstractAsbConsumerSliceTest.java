package uk.gov.hmcts.cp.notification.integration.base;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import uk.gov.hmcts.cp.notification.integration.config.AsbConsumerSliceConfig;
import uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport;
import uk.gov.hmcts.cp.notification.service.NotificationIngestionService;

/**
 * Base for the sliced consumer tests. The email and letter consumer tests must share one application context:
 * each context starts a processor on both slice queues, so two contexts would compete for the same messages
 * and a command could reach the other test's mock.
 */
@SpringBootTest(classes = AsbConsumerSliceConfig.class)
@ActiveProfiles("test")
public abstract class AbstractAsbConsumerSliceTest {

    @DynamicPropertySource
    static void serviceBusSliceProperties(final DynamicPropertyRegistry registry) {
        ServiceBusContainerSupport.registerSliceProperties(registry);
    }

    @MockitoBean
    protected NotificationIngestionService ingestionService;
}
