package uk.gov.hmcts.cp.notification.sender;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.UUID.fromString;
import static org.assertj.core.api.Assertions.assertThat;

class SendEmailRequestTest {

    @Test
    void should_show_only_the_ids_and_mask_every_personal_value_in_its_text() {
        final SendEmailRequest request = SendEmailRequest.builder()
                .notificationId(fromString("0f2b7c1e-3a4d-4e5f-8a9b-1c2d3e4f5a6b"))
                .templateId(fromString("9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b"))
                .emailAddress("alex.morgan@example.com")
                .personalisation(Map.of("name", "Alex Morgan"))
                .attachment(new byte[] {1, 2, 3})
                .attachmentFilename("alex-morgan-hearing.pdf")
                .replyToAddress("court.office@example.com")
                .replyToAddressId(fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"))
                .build();

        assertThat(request.toString()).isEqualTo("""
                SendEmailRequest[\
                notificationId=0f2b7c1e-3a4d-4e5f-8a9b-1c2d3e4f5a6b, \
                templateId=9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b, \
                emailAddress=<redacted>, \
                personalisation=<redacted>, \
                attachment=<redacted>, \
                attachmentFilename=<redacted>, \
                replyToAddress=<redacted>, \
                replyToAddressId=1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d]""");
    }
}
