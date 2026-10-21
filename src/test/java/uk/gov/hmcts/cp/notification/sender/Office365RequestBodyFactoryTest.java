package uk.gov.hmcts.cp.notification.sender;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailRequestFactory.aSendEmailRequest;

class Office365RequestBodyFactoryTest {
    private static final byte[] DOCUMENT = "large document".getBytes(StandardCharsets.UTF_8);

    private final Office365RequestBodyFactory factory = new Office365RequestBodyFactory(JsonMapper.builder().build());

    @Test
    void should_build_the_legacy_body_with_the_attachment_base64_encoded() {
        final UUID notificationId = UUID.randomUUID();
        final UUID templateId = UUID.randomUUID();
        final UUID replyToAddressId = UUID.randomUUID();

        final String json = factory.jsonFor(aSendEmailRequest()
                .notificationId(notificationId)
                .templateId(templateId)
                .emailAddress("reports@example.com")
                .personalisation(Map.of("recipient_name", "Alex Morgan"))
                .attachment(DOCUMENT)
                .attachmentFilename("d20.pdf")
                .replyToAddressId(replyToAddressId)
                .build());

        assertThatJson(json).isEqualTo("""
                {
                  "emailAddress": "reports@example.com",
                  "templateId": "%s",
                  "reference": "%s",
                  "personalisation": {"recipient_name": "Alex Morgan"},
                  "attachment": {"filename": "d20.pdf", "content": "%s"},
                  "replyToAddressId": "%s"
                }
                """.formatted(templateId, notificationId, Base64.getEncoder().encodeToString(DOCUMENT), replyToAddressId));
    }

    @Test
    void should_send_empty_personalisation_and_leave_out_absent_values() {
        final String json = factory.jsonFor(aSendEmailRequest()
                .emailAddress("")
                .personalisation(null)
                .attachmentFilename(null)
                .replyToAddressId(null)
                .build());

        assertThatJson(json).node("personalisation").isObject().isEmpty();
        assertThatJson(json).node("emailAddress").isAbsent();
        assertThatJson(json).node("replyToAddressId").isAbsent();
        assertThatJson(json).node("attachment.filename").isAbsent();
    }

    @Test
    void should_fail_permanently_without_a_status_when_the_body_cannot_be_serialised() {
        assertThatThrownBy(() -> factory.jsonFor(aSendEmailRequest()
                .personalisation(Map.of("unserialisable", new UnserialisableValue()))
                .build()))
                .isInstanceOf(Office365Exception.class)
                .hasMessage("Office 365 request could not be built")
                .hasFieldOrPropertyWithValue("httpStatus", null)
                .hasFieldOrPropertyWithValue("permanent", true);
    }

    public static final class UnserialisableValue {
        public String getValue() {
            throw new IllegalStateException("cannot be serialised");
        }
    }
}
