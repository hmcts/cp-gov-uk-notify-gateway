package uk.gov.hmcts.cp.notification.sender;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.of;
import static java.util.UUID.fromString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static uk.gov.hmcts.cp.notification.integration.testdata.AttachmentFactory.MATERIAL_DOCUMENT_FILENAME;
import static uk.gov.hmcts.cp.notification.integration.testdata.AttachmentFactory.aFileAttachment;
import static uk.gov.hmcts.cp.notification.integration.testdata.AttachmentFactory.aMaterialDocumentAttachment;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommand;

import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;

class SendEmailRequestMapperTest {
    private final SendEmailRequestMapper sendEmailRequestMapper = new SendEmailRequestMapper();

    @Test
    void should_copy_the_command_and_the_attachment_content_into_the_request() {
        final byte[] content = "report".getBytes(UTF_8);
        final SendEmailCommand command = aSendEmailCommand()
                .notificationId(fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80"))
                .templateId(fromString("9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b"))
                .sendToAddress("user@example.com")
                .personalisation(of("name", "Alex Morgan"))
                .replyToAddress("court.office@example.com")
                .replyToAddressId(fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"))
                .build();

        final SendEmailRequest request = sendEmailRequestMapper.requestFor(command, aFileAttachment(content));

        assertThat(request.notificationId()).isEqualTo(fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80"));
        assertThat(request.templateId()).isEqualTo(fromString("9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b"));
        assertThat(request.emailAddress()).isEqualTo("user@example.com");
        assertThat(request.personalisation()).isEqualTo(of("name", "Alex Morgan"));
        assertThat(request.attachment()).isEqualTo(content);
        assertThat(request.replyToAddress()).isEqualTo("court.office@example.com");
        assertThat(request.replyToAddressId()).isEqualTo(fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"));
    }

    @Test
    void should_have_no_attachment_or_attachment_filename_when_there_is_no_attachment() {
        final SendEmailRequest request = sendEmailRequestMapper.requestFor(aSendEmailCommand().build(), null);

        assertThat(request.attachment(), is(nullValue()));
        assertThat(request.attachmentFilename(), is(nullValue()));
    }

    @Test
    void should_use_the_filename_the_download_reported_rather_than_one_from_the_file_uri() {
        final SendEmailCommand command =
                aSendEmailCommand().fileUri("https://sa.blob.core.windows.net/mi-reportdata/report.csv").build();

        final SendEmailRequest request = sendEmailRequestMapper.requestFor(command, aMaterialDocumentAttachment());

        assertThat(request.attachmentFilename()).isEqualTo(MATERIAL_DOCUMENT_FILENAME);
    }

    @Test
    void should_name_the_attachment_after_the_last_segment_of_the_blob_uri() {
        assertThat(filenameFromFileUri("https://sa.blob.core.windows.net/mi-reportdata/2026/07/report.csv"))
                .isEqualTo("report.csv");
    }

    @Test
    void should_percent_decode_the_attachment_filename() {
        assertThat(filenameFromFileUri("https://sa.blob.core.windows.net/mi-reportdata/report%20final.csv"))
                .isEqualTo("report final.csv");
    }

    @Test
    void should_ignore_the_sas_query_string_when_naming_the_attachment() {
        assertThat(filenameFromFileUri(
                "https://sa.blob.core.windows.net/mi-reportdata/report.csv?sv=2020-04-08&sig=abc%3D"))
                .isEqualTo("report.csv");
    }

    @Test
    void should_not_name_the_attachment_when_there_is_no_file_uri() {
        assertThat(filenameFromFileUri(null), is(nullValue()));
    }

    @Test
    void should_not_name_the_attachment_when_the_file_uri_is_blank() {
        assertThat(filenameFromFileUri("  "), is(nullValue()));
    }

    @Test
    void should_not_name_the_attachment_when_the_uri_addresses_a_container_with_no_blob() {
        assertThat(filenameFromFileUri("https://sa.blob.core.windows.net/mi-reportdata"), is(nullValue()));
    }

    @Test
    void should_not_name_the_attachment_when_the_blob_name_is_a_directory_marker() {
        assertThat(filenameFromFileUri("https://sa.blob.core.windows.net/mi-reportdata/2026/07/"), is(nullValue()));
    }

    private String filenameFromFileUri(final String fileUri) {
        final SendEmailCommand command = aSendEmailCommand().fileUri(fileUri).build();
        final SendEmailRequest request = sendEmailRequestMapper.requestFor(command, aFileAttachment());
        return request.attachmentFilename();
    }
}
