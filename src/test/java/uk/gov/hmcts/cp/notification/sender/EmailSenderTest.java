package uk.gov.hmcts.cp.notification.sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.integration.testdata.AttachmentFactory.MATERIAL_DOCUMENT_FILENAME;
import static uk.gov.hmcts.cp.notification.integration.testdata.AttachmentFactory.aFileAttachment;
import static uk.gov.hmcts.cp.notification.integration.testdata.AttachmentFactory.aMaterialDocumentAttachment;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommand;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendResultFactory.aSendResult;

@ExtendWith(MockitoExtension.class)
class EmailSenderTest {

    @Mock
    private EmailClientFactory senderFactory;
    @Mock
    private EmailClient emailClient;

    @InjectMocks
    private EmailSender emailSender;

    @Captor
    private ArgumentCaptor<SendEmailRequest> requestCaptor;

    @Test
    void routes_the_request_built_from_the_command_and_attachment_to_the_selected_client() {
        final UUID id = UUID.randomUUID();
        final byte[] attachment = "report".getBytes(StandardCharsets.UTF_8);
        when(senderFactory.selectFor(any(SendEmailRequest.class))).thenReturn(emailClient);
        when(emailClient.send(any(SendEmailRequest.class))).thenReturn(aSendResult().reference("ref-1").build());

        final SendResult result = emailSender.sendEmail(
                aSendEmailCommand().notificationId(id).sendToAddress("user@example.com").build(),
                aFileAttachment(attachment));

        verify(senderFactory).selectFor(requestCaptor.capture());
        assertThat(requestCaptor.getValue().notificationId()).isEqualTo(id);
        assertThat(requestCaptor.getValue().emailAddress()).isEqualTo("user@example.com");
        assertThat(requestCaptor.getValue().attachment()).isEqualTo(attachment);
        assertThat(result.reference()).isEqualTo("ref-1");
    }

    @Test
    void sends_a_request_with_no_attachment_when_none_is_supplied() {
        final UUID id = UUID.randomUUID();
        when(senderFactory.selectFor(any(SendEmailRequest.class))).thenReturn(emailClient);
        when(emailClient.send(any(SendEmailRequest.class))).thenReturn(aSendResult().build());

        emailSender.sendEmail(aSendEmailCommand().notificationId(id).build(), null);

        verify(senderFactory).selectFor(requestCaptor.capture());
        assertThat(requestCaptor.getValue().attachment()).isNull();
    }

    @Test
    void uses_the_filename_reported_by_the_download_rather_than_deriving_one_from_the_file_uri() {
        stubClient();
        final Attachment document = aMaterialDocumentAttachment();

        emailSender.sendEmail(aSendEmailCommand().fileUri(
                "https://sa.blob.core.windows.net/mi-reportdata/report.csv").build(), document);

        verify(senderFactory).selectFor(requestCaptor.capture());
        assertThat(requestCaptor.getValue().attachment()).isEqualTo(document.content());
        assertThat(requestCaptor.getValue().attachmentFilename()).isEqualTo(MATERIAL_DOCUMENT_FILENAME);
    }

    @Test
    void derives_the_attachment_filename_from_the_last_segment_of_the_blob_uri() {
        stubClient();

        emailSender.sendEmail(aSendEmailCommand().fileUri(
                "https://sa.blob.core.windows.net/mi-reportdata/2026/07/report.csv").build(), someAttachment());

        verify(senderFactory).selectFor(requestCaptor.capture());
        assertThat(requestCaptor.getValue().attachmentFilename()).isEqualTo("report.csv");
    }

    @Test
    void percent_decodes_the_attachment_filename() {
        stubClient();

        emailSender.sendEmail(aSendEmailCommand().fileUri(
                "https://sa.blob.core.windows.net/mi-reportdata/report%20final.csv").build(), someAttachment());

        verify(senderFactory).selectFor(requestCaptor.capture());
        assertThat(requestCaptor.getValue().attachmentFilename()).isEqualTo("report final.csv");
    }

    @Test
    void ignores_the_sas_query_string_when_deriving_the_attachment_filename() {
        stubClient();

        emailSender.sendEmail(aSendEmailCommand().fileUri(
                "https://sa.blob.core.windows.net/mi-reportdata/report.csv?sv=2020-04-08&sig=abc%3D").build(),
                someAttachment());

        verify(senderFactory).selectFor(requestCaptor.capture());
        assertThat(requestCaptor.getValue().attachmentFilename()).isEqualTo("report.csv");
    }

    @Test
    void derives_no_attachment_filename_when_the_file_uri_is_blank() {
        stubClient();

        emailSender.sendEmail(aSendEmailCommand().fileUri("  ").build(), someAttachment());

        verify(senderFactory).selectFor(requestCaptor.capture());
        assertThat(requestCaptor.getValue().attachmentFilename()).isNull();
    }

    @Test
    void derives_no_attachment_filename_when_the_uri_addresses_a_container_with_no_blob() {
        stubClient();

        emailSender.sendEmail(aSendEmailCommand().fileUri(
                "https://sa.blob.core.windows.net/mi-reportdata").build(), someAttachment());

        verify(senderFactory).selectFor(requestCaptor.capture());
        assertThat(requestCaptor.getValue().attachmentFilename()).isNull();
    }

    @Test
    void derives_no_attachment_filename_when_the_blob_name_is_a_directory_marker() {
        stubClient();

        emailSender.sendEmail(aSendEmailCommand().fileUri(
                "https://sa.blob.core.windows.net/mi-reportdata/2026/07/").build(), someAttachment());

        verify(senderFactory).selectFor(requestCaptor.capture());
        assertThat(requestCaptor.getValue().attachmentFilename()).isNull();
    }

    private void stubClient() {
        when(senderFactory.selectFor(any(SendEmailRequest.class))).thenReturn(emailClient);
        when(emailClient.send(any(SendEmailRequest.class))).thenReturn(aSendResult().build());
    }

    private static Attachment someAttachment() {
        return aFileAttachment();
    }
}
