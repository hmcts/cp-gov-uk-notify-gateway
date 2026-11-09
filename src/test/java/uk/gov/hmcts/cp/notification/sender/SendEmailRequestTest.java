package uk.gov.hmcts.cp.notification.sender;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailRequestFactory.aSendEmailRequest;

class SendEmailRequestTest {

    @Test
    void text_shows_the_recipient_but_not_the_personalisation_attachment_name_or_reply_to() {
        final SendEmailRequest request = aSendEmailRequest()
                .emailAddress("alex.morgan@example.com")
                .personalisation(Map.of("name", "Alex Morgan"))
                .attachmentFilename("alex-morgan-hearing.pdf")
                .replyToAddress("court.office@example.com")
                .build();

        assertThat(request.toString())
                .contains(request.notificationId().toString(), "alex.morgan@example.com")
                .doesNotContain("Alex Morgan", "alex-morgan-hearing.pdf",
                        "court.office@example.com");
    }
}
