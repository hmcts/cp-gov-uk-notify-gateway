package uk.gov.hmcts.cp.notification.command;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommand;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommandWithMaterialUrl;

class SendEmailCommandTest {

    @Test
    void should_show_the_ids_but_not_the_recipient_reply_to_or_personalisation_in_its_text() {
        final UUID notificationId = UUID.randomUUID();
        final SendEmailCommand command = aSendEmailCommand()
                .notificationId(notificationId)
                .sendToAddress("alex.morgan@example.com")
                .replyToAddress("court.office@example.com")
                .personalisation(Map.of("name", "Alex Morgan"))
                .build();

        assertThat(command.toString())
                .contains(notificationId.toString())
                .doesNotContain("alex.morgan@example.com", "court.office@example.com", "Alex Morgan");
    }

    @Test
    void should_leave_the_attachment_links_out_of_its_text() {
        final SendEmailCommand command = aSendEmailCommandWithMaterialUrl().build();

        assertThat(command.toString()).doesNotContain(command.materialUrl());
    }
}
