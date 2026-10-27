package uk.gov.hmcts.cp.notification.acceptance.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import uk.gov.hmcts.cp.notification.integration.stubs.GovUkNotifyStubService;
import uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub;
import uk.gov.hmcts.cp.notification.integration.stubs.Office365StubService;

import static java.util.Map.of;
import static java.util.UUID.randomUUID;
import static uk.gov.hmcts.cp.notification.integration.Fixtures.load;
import static uk.gov.hmcts.cp.notification.integration.Fixtures.loadBytes;
import static uk.gov.hmcts.cp.notification.integration.stubs.ASBTestClient.anAsbTestClient;
import static uk.gov.hmcts.cp.notification.integration.stubs.AzureBlobFileStoreStub.anAzureBlobFileStore;
import static uk.gov.hmcts.cp.notification.integration.stubs.GovUkNotifyStubService.aGovUkNotifyService;
import static uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub.aMaterialService;
import static uk.gov.hmcts.cp.notification.integration.stubs.Office365StubService.anOffice365Service;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport.LETTER_COMMAND_QUEUE;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport.RESULT_QUEUE;

public class CommandSteps {
    private static final String MATERIAL_DOCUMENT_FILENAME = "d20.pdf";
    private static final String LETTER_FILENAME = "letter.pdf";
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;
    private static final int OVER_GOV_NOTIFY_ATTACHMENT_LIMIT = 2_097_153;

    @Value("${cp.notification.blob.max-attachment-bytes}")
    private long maxAttachmentBytes;

    @Autowired
    private ScenarioContext context;

    private final GovUkNotifyStubService govUkNotify = aGovUkNotifyService();
    private final MaterialServiceStub material = aMaterialService();
    private final Office365StubService office365 = anOffice365Service();

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

    @Given("a send-email-notification command for a recipient with a Material link to an unrecognised address")
    public void a_send_email_notification_command_with_a_material_link_to_an_unrecognised_address() {
        aMaterialNotificationIsPrepared();
        aCommandWithTheMaterialLink(material.unrecognisedLinkFor(context.getMaterialId()));
    }

    @Given("a send-email-notification command for a recipient with a Material document too large to send")
    public void a_send_email_notification_command_with_a_material_document_too_large_to_send() {
        aMaterialNotificationIsPrepared();
        material.documentLargerThanTheCapWillBeServed(context.getMaterialId(), MATERIAL_DOCUMENT_FILENAME, maxAttachmentBytes);
        aCommandWithTheMaterialLink(material.materialClientLinkFor(context.getMaterialId()));
    }

    @Given("a send-email-notification command for a recipient with a Material document larger than Gov.UK Notify accepts")
    public void a_send_email_notification_command_with_a_material_document_larger_than_gov_uk_notify_accepts() {
        aMaterialNotificationIsPrepared();
        final byte[] document = new byte[OVER_GOV_NOTIFY_ATTACHMENT_LIMIT];
        context.setAttachmentBytes(document);
        material.documentWillBeServed(context.getMaterialId(), MATERIAL_DOCUMENT_FILENAME, document);
        office365.sendEmailWillReturnSuccess(context.getTemplateId(), context.getNotificationId());
        aCommandWithTheMaterialLink(material.materialClientLinkFor(context.getMaterialId()));
    }

    @Given("a send-email-notification command for a recipient with a Material link that Material rejects as invalid")
    public void a_send_email_notification_command_with_a_material_link_that_material_rejects_as_invalid() {
        aMaterialNotificationIsPrepared();
        material.materialWillAnswer(context.getMaterialId(), HTTP_BAD_REQUEST);
        aCommandWithTheMaterialLink(material.materialClientLinkFor(context.getMaterialId()));
    }

    @Given("a send-email-notification command for a recipient with a Material document that is briefly unavailable")
    public void a_send_email_notification_command_with_a_material_document_that_is_briefly_unavailable() {
        aMaterialNotificationIsPrepared();
        material.documentWillBeUnavailableOnceThenServed(
                context.getMaterialId(), MATERIAL_DOCUMENT_FILENAME, context.getAttachmentBytes());
        aCommandWithTheMaterialLink(material.materialClientLinkFor(context.getMaterialId()));
    }

    @Given("a send-email-notification command for a recipient with a Material document the gateway is not allowed to fetch")
    public void a_send_email_notification_command_with_a_material_document_the_gateway_is_not_allowed_to_fetch() {
        aMaterialNotificationIsPrepared();
        material.materialWillAnswer(context.getMaterialId(), HTTP_FORBIDDEN);
        aCommandWithTheMaterialLink(material.materialClientLinkFor(context.getMaterialId()));
    }

    @Given("the originator provides a reply queue")
    public void the_originator_provides_a_reply_queue() {
        context.setReplyQueue(RESULT_QUEUE);
    }

    @Given("a send-letter-notification command for a letter held by the Material service")
    public void a_send_letter_notification_command_for_a_letter_held_by_the_material_service() {
        aLetterCommand("fixtures/commands/send-letter.json", false);
    }

    @Given("a first-class send-letter-notification command for a letter held by the Material service")
    public void a_first_class_send_letter_notification_command_for_a_letter_held_by_the_material_service() {
        aLetterCommand("fixtures/commands/send-first-class-letter.json", true);
    }

    @Given("a send-letter-notification command for a letter whose Material document stays unavailable")
    public void a_send_letter_notification_command_for_a_letter_whose_material_document_stays_unavailable() {
        aLetterCommand("fixtures/commands/send-letter.json", false);
        material.materialWillAnswer(context.getMaterialId(), HTTP_INTERNAL_SERVER_ERROR);
    }

    @Given("a send-letter-notification command for a letter whose Material document is briefly unavailable")
    public void a_send_letter_notification_command_for_a_letter_whose_material_document_is_briefly_unavailable() {
        aLetterCommand("fixtures/commands/send-letter.json", false);
        material.documentWillBeUnavailableOnceThenServed(
                context.getMaterialId(), LETTER_FILENAME, context.getAttachmentBytes());
    }

    @When("the gateway processes the command")
    public void the_gateway_processes_the_command() {
        anAsbTestClient(context.getCommandQueue()).sendToCommandQueue(context.getCommandJson(), context.getReplyQueue());
    }

    private void aLetterCommand(final String commandFixture, final boolean firstClass) {
        final String notificationId = randomUUID().toString();
        final String externalReference = randomUUID().toString();
        final String materialId = randomUUID().toString();
        final byte[] document = loadBytes("fixtures/attachments/material-document.pdf");

        material.documentWillBeServed(materialId, LETTER_FILENAME, document);
        govUkNotify
                .sendLetterWillReturnSuccess(externalReference)
                .letterStatusWillBe(externalReference, "received");

        context.setCommandQueue(LETTER_COMMAND_QUEUE);
        context.setNotificationId(notificationId);
        context.setExternalReference(externalReference);
        context.setMaterialId(materialId);
        context.setAttachmentBytes(document);
        context.setAttachmentFilename(LETTER_FILENAME);
        context.setFirstClass(firstClass);
        context.setMaterialUrl(material.materialClientLinkFor(materialId));
        context.setCommandJson(load(commandFixture, of(
                "notificationId", notificationId,
                "letterUrl", context.getMaterialUrl())));
    }

    private void aMaterialDocumentIsAvailable() {
        aMaterialNotificationIsPrepared();
        material.documentWillBeServed(context.getMaterialId(), MATERIAL_DOCUMENT_FILENAME, context.getAttachmentBytes());
    }

    private void aCommandWithTheMaterialLink(final String materialUrl) {
        context.setMaterialUrl(materialUrl);
        context.setCommandJson(load("fixtures/commands/send-email-with-material-url.json", of(
                "notificationId", context.getNotificationId(),
                "templateId", context.getTemplateId(),
                "materialUrl", materialUrl)));
    }

    private void aMaterialNotificationIsPrepared() {
        final String notificationId = randomUUID().toString();
        final String templateId = randomUUID().toString();
        final String externalReference = randomUUID().toString();
        final String materialId = randomUUID().toString();
        final byte[] document = loadBytes("fixtures/attachments/material-document.pdf");

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
