package uk.gov.hmcts.cp.notification.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import uk.gov.hmcts.cp.notification.integration.base.AbstractEndToEndIntegrationTest;
import uk.gov.hmcts.cp.notification.integration.repository.JobTestRepository;
import uk.gov.hmcts.cp.notification.integration.repository.NotificationTestRepository;
import uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub;
import uk.gov.hmcts.cp.notification.integration.stubs.Office365StubService;
import uk.gov.hmcts.cp.notification.integration.stubs.support.WireMockSupport;
import uk.gov.hmcts.cp.notification.persistence.NotificationEntity;

import java.util.Optional;
import java.util.UUID;

import static java.time.Duration.ofSeconds;
import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static uk.gov.hmcts.cp.notification.integration.Fixtures.load;
import static uk.gov.hmcts.cp.notification.integration.stubs.ASBTestClient.anAsbTestClient;
import static uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub.aMaterialService;
import static uk.gov.hmcts.cp.notification.integration.stubs.Office365StubService.anOffice365Service;

class RecordSentTaskIntegrationTest extends AbstractEndToEndIntegrationTest {
    private static final String UNPROVISIONED_REPLY_QUEUE = "ng-result-not-provisioned";
    private static final int OVER_GOV_NOTIFY_ATTACHMENT_LIMIT = 2_097_153;

    @Autowired
    private NotificationTestRepository notifications;

    @Autowired
    private JobTestRepository jobs;

    private final MaterialServiceStub material = aMaterialService();
    private final Office365StubService office365 = anOffice365Service();

    @BeforeEach
    void resetStubs() {
        WireMockSupport.reset();
    }

    @AfterEach
    void cleanUp() {
        jobs.deleteAll();
        notifications.deleteAll();
    }

    @Test
    void parks_the_record_sent_job_without_resending_the_email_when_recording_sent_keeps_failing() {
        final String notificationId = UUID.randomUUID().toString();
        final String templateId = UUID.randomUUID().toString();
        final String materialId = UUID.randomUUID().toString();
        material.documentWillBeServed(materialId, "d20.pdf", new byte[OVER_GOV_NOTIFY_ATTACHMENT_LIMIT]);
        office365.sendEmailWillReturnSuccess(templateId, notificationId);

        anAsbTestClient().sendToCommandQueue(load("fixtures/commands/send-email-with-material-url.json", of(
                "notificationId", notificationId,
                "templateId", templateId,
                "materialUrl", material.materialClientLinkFor(materialId))), UNPROVISIONED_REPLY_QUEUE);

        await().atMost(ofSeconds(90)).untilAsserted(() ->
                assertThat(jobs.retryAttemptsRemainingForTask(RecordSentTask.TASK_NAME)).contains(0));
        await().during(ofSeconds(3)).atMost(ofSeconds(5)).untilAsserted(() ->
                assertThat(jobs.retryAttemptsRemainingForTask(RecordSentTask.TASK_NAME)).contains(0));

        office365.emailWasSentOnce();
        assertThat(jobs.countForTask(SendEmailTask.TASK_NAME)).isZero();
        final Optional<NotificationEntity> notification = notifications.findById(UUID.fromString(notificationId));
        assertThat(notification).map(NotificationEntity::getStatus).contains("QUEUED");
    }
}
