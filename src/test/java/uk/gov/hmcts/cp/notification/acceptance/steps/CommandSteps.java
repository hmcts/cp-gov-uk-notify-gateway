package uk.gov.hmcts.cp.notification.acceptance.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;

import uk.gov.hmcts.cp.notification.integration.stubs.GovUkNotifyStubService;
import uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub;

import static java.util.Map.of;
import static java.util.UUID.randomUUID;
import static uk.gov.hmcts.cp.notification.integration.Fixtures.load;
import static uk.gov.hmcts.cp.notification.integration.Fixtures.loadBytes;
import static uk.gov.hmcts.cp.notification.integration.stubs.ASBTestClient.anAsbTestClient;
import static uk.gov.hmcts.cp.notification.integration.stubs.AzureBlobFileStoreStub.anAzureBlobFileStore;
import static uk.gov.hmcts.cp.notification.integration.stubs.GovUkNotifyStubService.aGovUkNotifyService;
import static uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub.aMaterialService;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport.RESULT_QUEUE;

public class CommandSteps {
    private static final String MATERIAL_DOCUMENT_FILENAME = "d20.pdf";

    @Autowired
    private ScenarioContext context;

    private final GovUkNotifyStubService govUkNotify = aGovUkNotifyService();
    private final MaterialServiceStub material = aMaterialService();

    @Given("a send-email-notification command for a recipient with an attachment")
    public void a_send_email_notification_command_for_a_recipient_with_an_attachment() {
        final String notificationId = randomUUID().toString();
        final String templateId = randomUUID().toString();
        final String externalReference = randomUUID().toString();
        final byte[] attachmentBytes = loadBytes("fixtures/attachments/report.csv");

        final String blobName = "report-" + notificationId + ".csv";
        final String fileUri = anAzureBlobFileStore()
                .containing(blobName, attachmentBytes)
                .uriOf(blobName);

        govUkNotify
                .sendEmailNotificationWillReturnSuccess(externalReference)
                .getNotificationStatusWillReturnSuccess(externalReference);

        context.setNotificationId(notificationId);
        context.setTemplateId(templateId);
        context.setExternalReference(externalReference);
        context.setAttachmentBytes(attachmentBytes);
        context.setCommandJson(load("fixtures/commands/send-email-with-attachment.json", of(
                "notificationId", notificationId,
                "templateId", templateId,
                "fileUri", fileUri)));
    }

    @Given("a send-email-notification command whose attachment is missing")
    public void a_send_email_notification_command_whose_attachment_is_missing() {
        final String notificationId = randomUUID().toString();
        final String templateId = randomUUID().toString();
        final String fileUri = anAzureBlobFileStore().uriOf("missing-" + notificationId + ".csv");

        context.setNotificationId(notificationId);
        context.setTemplateId(templateId);
        context.setCommandJson(load("fixtures/commands/send-email-missing-attachment.json", of(
                "notificationId", notificationId,
                "templateId", templateId,
                "fileUri", fileUri)));
    }

    @Given("a send-email-notification command for a recipient with a Material document link")
    public void a_send_email_notification_command_for_a_recipient_with_a_material_document_link() {
        aMaterialDocumentIsAvailable();
        context.setMaterialUrl(material.materialClientLinkFor(context.getMaterialId()));
        context.setCommandJson(load("fixtures/commands/send-email-with-material-url.json", of(
                "notificationId", context.getNotificationId(),
                "templateId", context.getTemplateId(),
                "materialUrl", context.getMaterialUrl())));
    }

    @Given("a send-email-notification command for a recipient with a Material document link that already points at the Material service")
    public void a_send_email_notification_command_with_a_material_document_link_on_the_material_service() {
        aMaterialDocumentIsAvailable();
        context.setMaterialUrl(material.materialServiceLinkFor(context.getMaterialId()));
        context.setCommandJson(load("fixtures/commands/send-email-with-material-url.json", of(
                "notificationId", context.getNotificationId(),
                "templateId", context.getTemplateId(),
                "materialUrl", context.getMaterialUrl())));
    }

    @Given("a send-email-notification command for a recipient with both a Material document link and a file")
    public void a_send_email_notification_command_with_both_a_material_document_link_and_a_file() {
        aMaterialDocumentIsAvailable();
        final String blobName = "report-" + context.getNotificationId() + ".csv";
        final String fileUri = anAzureBlobFileStore()
                .containing(blobName, loadBytes("fixtures/attachments/report.csv"))
                .uriOf(blobName);
        context.setMaterialUrl(material.materialClientLinkFor(context.getMaterialId()));
        context.setCommandJson(load("fixtures/commands/send-email-with-material-url-and-attachment.json", of(
                "notificationId", context.getNotificationId(),
                "templateId", context.getTemplateId(),
                "materialUrl", context.getMaterialUrl(),
                "fileUri", fileUri)));
    }

    @Given("the originator provides a reply queue")
    public void the_originator_provides_a_reply_queue() {
        context.setReplyQueue(RESULT_QUEUE);
    }

    @When("the gateway processes the command")
    public void the_gateway_processes_the_command() {
        anAsbTestClient().sendToCommandQueue(context.getCommandJson(), context.getReplyQueue());
    }

    private void aMaterialDocumentIsAvailable() {
        final String notificationId = randomUUID().toString();
        final String templateId = randomUUID().toString();
        final String externalReference = randomUUID().toString();
        final String materialId = randomUUID().toString();
        final byte[] document = loadBytes("fixtures/attachments/material-document.pdf");

        material.documentWillBeServed(materialId, MATERIAL_DOCUMENT_FILENAME, document);
        govUkNotify
                .sendEmailNotificationWillReturnSuccess(externalReference)
                .getNotificationStatusWillReturnSuccess(externalReference);

        context.setNotificationId(notificationId);
        context.setTemplateId(templateId);
        context.setExternalReference(externalReference);
        context.setMaterialId(materialId);
        context.setAttachmentBytes(document);
        context.setAttachmentFilename(MATERIAL_DOCUMENT_FILENAME);
    }
}
