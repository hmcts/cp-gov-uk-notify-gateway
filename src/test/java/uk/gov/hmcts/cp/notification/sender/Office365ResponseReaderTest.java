package uk.gov.hmcts.cp.notification.sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailRequestFactory.aSendEmailRequest;

class Office365ResponseReaderTest {
    private static final String RESPONSE = """
            {"subject": "Your document is ready",
             "templateContent": "Your document is ready\\n<p style=\\"font-size: 19px;\\">Courts &amp; Tribunals: please find the document attached.</p>"}
            """;

    private final Office365ResponseReader reader = new Office365ResponseReader(JsonMapper.builder().build());

    @Test
    void should_read_the_subject_and_the_text_of_the_second_template_line_as_a_result_already_sent() {
        final UUID notificationId = UUID.randomUUID();

        final SendResult result = reader.sentResult(aSendEmailRequest().notificationId(notificationId).build(), RESPONSE);

        assertThat(result.sentOnSubmit()).isTrue();
        assertThat(result.reference()).isEqualTo(notificationId.toString());
        assertThat(result.emailSubject()).isEqualTo("Your document is ready");
        assertThat(result.emailBody()).isEqualTo("Courts & Tribunals: please find the document attached.");
    }

    @Test
    void should_record_the_no_reply_address_when_the_command_has_no_reply_to_address() {
        final SendResult result = reader.sentResult(aSendEmailRequest().replyToAddress(null).build(), RESPONSE);

        assertThat(result.replyToAddress()).isEqualTo("NOREPLY@noreply.com");
    }

    @Test
    void should_record_the_command_reply_to_address_when_present() {
        final SendResult result = reader.sentResult(
                aSendEmailRequest().replyToAddress("court@example.com").build(), RESPONSE);

        assertThat(result.replyToAddress()).isEqualTo("court@example.com");
    }

    @Test
    void should_give_an_empty_body_when_the_template_has_a_single_line() {
        final SendResult result = reader.sentResult(aSendEmailRequest().build(),
                "{\"subject\": \"Subject only\", \"templateContent\": \"Subject only\"}");

        assertThat(result.emailSubject()).isEqualTo("Subject only");
        assertThat(result.emailBody()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "", "[1, 2]", "{}"})
    void should_give_a_result_already_sent_without_subject_or_body_when_the_response_cannot_be_read(
            final String responseBody) {
        final SendResult result = reader.sentResult(aSendEmailRequest().build(), responseBody);

        assertThat(result.sentOnSubmit()).isTrue();
        assertThat(result.emailSubject()).isEmpty();
        assertThat(result.emailBody()).isEmpty();
    }
}
