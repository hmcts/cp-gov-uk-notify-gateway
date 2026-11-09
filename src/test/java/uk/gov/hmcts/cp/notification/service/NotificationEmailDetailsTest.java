package uk.gov.hmcts.cp.notification.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEmailDetailsTest {

    @Test
    void text_leaves_out_the_email_as_sent() {
        final NotificationEmailDetails details = new NotificationEmailDetails(
                "Your hearing on 1 December", "Dear Alex Morgan", "court.office@example.com");

        assertThat(details.toString())
                .doesNotContain("Your hearing on 1 December", "Dear Alex Morgan", "court.office@example.com");
    }
}
