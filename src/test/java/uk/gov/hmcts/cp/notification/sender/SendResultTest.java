package uk.gov.hmcts.cp.notification.sender;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendResultFactory.aSendResult;

class SendResultTest {

    @Test
    void text_shows_the_reference_but_not_the_email_as_sent() {
        final SendResult result = aSendResult()
                .emailSubject("Your hearing on 1 December")
                .emailBody("Dear Alex Morgan")
                .replyToAddress("court.office@example.com")
                .build();

        assertThat(result.toString())
                .contains(result.reference())
                .doesNotContain("Your hearing on 1 December", "Dear Alex Morgan", "court.office@example.com");
    }

    @Test
    void text_shows_which_parts_of_the_email_are_absent() {
        final SendResult result = aSendResult().emailSubject("Your hearing on 1 December").build();

        assertThat(result.toString()).contains("emailSubject=<redacted>", "emailBody=null", "replyToAddress=null");
    }
}
