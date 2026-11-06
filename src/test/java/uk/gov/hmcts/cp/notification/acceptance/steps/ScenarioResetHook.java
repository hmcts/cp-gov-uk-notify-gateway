package uk.gov.hmcts.cp.notification.acceptance.steps;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import org.springframework.beans.factory.annotation.Autowired;

import uk.gov.hmcts.cp.notification.integration.repository.JobTestRepository;
import uk.gov.hmcts.cp.notification.integration.repository.NotificationTestRepository;
import uk.gov.hmcts.cp.notification.integration.stubs.support.WireMockSupport;

import static uk.gov.hmcts.cp.notification.integration.stubs.ASBTestClient.anAsbTestClient;
import static uk.gov.hmcts.cp.notification.integration.stubs.ResultQueueStubService.aResultQueue;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport.COMMAND_QUEUE;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport.LETTER_COMMAND_QUEUE;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport.RESULT_QUEUE;

public class ScenarioResetHook {

    @Autowired
    private NotificationTestRepository notifications;

    @Autowired
    private JobTestRepository jobs;

    @Before
    public void resetStubs() {
        WireMockSupport.reset();
        aResultQueue(RESULT_QUEUE).drain();
        anAsbTestClient(COMMAND_QUEUE).purgeDeadLetterQueue();
        anAsbTestClient(LETTER_COMMAND_QUEUE).purgeDeadLetterQueue();
    }

    @After
    public void cleanUp() {
        jobs.deleteAll();
        notifications.deleteAll();
    }
}
