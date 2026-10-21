package uk.gov.hmcts.cp.notification.acceptance.steps;

import io.cucumber.java.en.Then;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.hmcts.cp.notification.integration.stubs.Office365StubService;

import static java.time.Duration.ofSeconds;
import static java.util.Map.of;
import static org.awaitility.Awaitility.await;
import static uk.gov.hmcts.cp.notification.integration.Fixtures.load;
import static uk.gov.hmcts.cp.notification.integration.stubs.Office365StubService.anOffice365Service;

public class Office365Steps {

    @Autowired
    private ScenarioContext context;

    private final Office365StubService office365 = anOffice365Service();

    @Then("the email is sent via the Office 365 provider with the Material document attached")
    public void the_email_is_sent_via_the_office365_provider_with_the_material_document_attached() {
        final String expectedRequest = load("fixtures/office365/expected-send-email-request-with-material-document.json", of(
                "templateId", context.getTemplateId(),
                "notificationId", context.getNotificationId(),
                "filename", context.getAttachmentFilename()));
        final byte[] document = context.getAttachmentBytes();
        await().atMost(ofSeconds(60))
                .untilAsserted(() -> office365.sendEmailRequestMatches(expectedRequest, document));
    }
}
