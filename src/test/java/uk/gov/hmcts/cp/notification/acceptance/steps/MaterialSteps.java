package uk.gov.hmcts.cp.notification.acceptance.steps;

import io.cucumber.java.en.Then;
import org.springframework.beans.factory.annotation.Autowired;

import uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub;
import uk.gov.hmcts.cp.notification.material.MaterialSettings;

import java.util.UUID;

import static java.time.Duration.ofSeconds;
import static org.awaitility.Awaitility.await;
import static uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub.aMaterialService;

public class MaterialSteps {

    @Autowired
    private ScenarioContext context;

    @Autowired
    private MaterialSettings materialSettings;

    private final MaterialServiceStub material = aMaterialService();

    @Then("the document is fetched from the Material service on behalf of the notification")
    public void the_document_is_fetched_from_the_material_service_on_behalf_of_the_notification() {
        final String materialId = context.getMaterialId();
        final UUID notificationId = UUID.fromString(context.getNotificationId());
        final String filename = context.getAttachmentFilename();
        final UUID systemUserId = materialSettings.systemUserId();
        await().atMost(ofSeconds(60)).untilAsserted(() -> material
                .documentWasRequestedFor(materialId, notificationId, systemUserId)
                .documentWasDownloadedWithoutCredentials(materialId, filename));
    }
}
