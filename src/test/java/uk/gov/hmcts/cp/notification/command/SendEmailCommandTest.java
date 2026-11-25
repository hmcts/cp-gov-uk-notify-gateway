package uk.gov.hmcts.cp.notification.command;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.UUID.fromString;
import static org.assertj.core.api.Assertions.assertThat;

class SendEmailCommandTest {

    @Test
    void should_show_only_the_ids_and_mask_every_personal_value_in_its_text() {
        final SendEmailCommand command = SendEmailCommand.builder()
                .notificationId(fromString("0f2b7c1e-3a4d-4e5f-8a9b-1c2d3e4f5a6b"))
                .templateId(fromString("9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b"))
                .sendToAddress("alex.morgan@example.com")
                .fileUri("https://sa.blob.core.windows.net/reports/alex-morgan.csv")
                .materialUrl("http://material/query/api/rest/material/material/alex-morgan")
                .replyToAddress("court.office@example.com")
                .replyToAddressId(fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"))
                .personalisation(Map.of("name", "Alex Morgan"))
                .clientContext("case for Alex Morgan")
                .build();

        assertThat(command.toString()).isEqualTo("""
                SendEmailCommand[\
                notificationId=0f2b7c1e-3a4d-4e5f-8a9b-1c2d3e4f5a6b, \
                templateId=9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b, \
                sendToAddress=<redacted>, \
                replyToAddress=<redacted>, \
                personalisation=<redacted>]""");
    }

    @Test
    void should_show_which_optional_personal_values_were_not_given() {
        final SendEmailCommand command = SendEmailCommand.builder()
                .notificationId(fromString("0f2b7c1e-3a4d-4e5f-8a9b-1c2d3e4f5a6b"))
                .templateId(fromString("9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b"))
                .sendToAddress("alex.morgan@example.com")
                .build();

        assertThat(command.toString()).isEqualTo("""
                SendEmailCommand[\
                notificationId=0f2b7c1e-3a4d-4e5f-8a9b-1c2d3e4f5a6b, \
                templateId=9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b, \
                sendToAddress=<redacted>, \
                replyToAddress=null, \
                personalisation=null]""");
    }
}
