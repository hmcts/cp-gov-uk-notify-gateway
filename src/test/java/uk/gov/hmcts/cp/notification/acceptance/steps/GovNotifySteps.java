package uk.gov.hmcts.cp.notification.acceptance.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import org.springframework.beans.factory.annotation.Autowired;

import uk.gov.hmcts.cp.notification.integration.stubs.GovUkNotifyStubService;

import static java.time.Duration.ofSeconds;
import static java.util.Map.of;
import static org.awaitility.Awaitility.await;
import static uk.gov.hmcts.cp.notification.integration.Fixtures.load;
import static uk.gov.hmcts.cp.notification.integration.stubs.GovUkNotifyStubService.aGovUkNotifyService;

public class GovNotifySteps {

    @Autowired
    private ScenarioContext context;

    private final GovUkNotifyStubService govUkNotify = aGovUkNotifyService();

    @Given("the provider reports the delivery as permanently failed")
    public void the_provider_reports_the_delivery_as_permanently_failed() {
        govUkNotify.getNotificationStatusWillReturnPermanentFailure(context.getExternalReference());
    }

    @Then("the email is sent via the Gov.UK Notify provider")
    public void the_email_is_sent_via_the_gov_uk_notify_provider() {
        final String expectedRequest = load("fixtures/gov-notify/expected-send-email-request.json", of(
                "templateId", context.getTemplateId(),
                "notificationId", context.getNotificationId()));
        final byte[] attachmentBytes = context.getAttachmentBytes();
        await().atMost(ofSeconds(60))
                .untilAsserted(() -> govUkNotify.sendEmailRequestMatches(expectedRequest, attachmentBytes));
    }

    @Then("the email is sent via the Gov.UK Notify provider with the Material document attached")
    public void the_email_is_sent_via_the_gov_uk_notify_provider_with_the_material_document_attached() {
        final String expectedRequest = load("fixtures/gov-notify/expected-send-email-request-with-material-document.json", of(
                "templateId", context.getTemplateId(),
                "notificationId", context.getNotificationId(),
                "filename", context.getAttachmentFilename()));
        final byte[] document = context.getAttachmentBytes();
        await().atMost(ofSeconds(60))
                .untilAsserted(() -> govUkNotify.sendEmailRequestMatches(expectedRequest, document));
    }

    @Then("the delivery status is polled from the provider")
    public void the_delivery_status_is_polled_from_the_provider() {
        final String externalReference = context.getExternalReference();
        await().atMost(ofSeconds(60))
                .untilAsserted(() -> govUkNotify.deliveryStatusWasPolledFor(externalReference));
    }

    @Then("no email is sent via the Gov.UK Notify provider")
    public void no_email_is_sent_via_the_gov_uk_notify_provider() {
        govUkNotify.emailNotificationWasNotSent();
    }
}
