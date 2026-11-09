package uk.gov.hmcts.cp.notification.command;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommand;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommandWithMaterialUrl;

class SendEmailCommandTest {

    @Test
    void text_shows_the_ids_and_recipient_but_not_the_reply_to_or_personalisation() {
        final UUID notificationId = UUID.randomUUID();
        final SendEmailCommand command = aSendEmailCommand()
                .notificationId(notificationId)
                .sendToAddress("alex.morgan@example.com")
                .replyToAddress("court.office@example.com")
                .personalisation(Map.of("name", "Alex Morgan"))
                .build();

        assertThat(command.toString())
                .contains(notificationId.toString(), "alex.morgan@example.com")
                .doesNotContain("court.office@example.com", "Alex Morgan");
    }

    @Test
    void text_leaves_out_the_attachment_links() {
        final SendEmailCommand command = aSendEmailCommandWithMaterialUrl().build();

        assertThat(command.toString()).doesNotContain(command.materialUrl());
    }
}
