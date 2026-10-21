package uk.gov.hmcts.cp.notification.sender;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import uk.gov.hmcts.cp.notification.integration.stubs.Office365StubService;
import uk.gov.hmcts.cp.notification.integration.stubs.support.WireMockSupport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.cp.notification.integration.Fixtures.load;
import static uk.gov.hmcts.cp.notification.integration.stubs.Office365StubService.anOffice365Service;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailRequestFactory.aSendEmailRequest;

class Office365ClientIntegrationTest {
    private static final byte[] DOCUMENT = "large document".getBytes(StandardCharsets.UTF_8);

    private final Office365StubService office365 = anOffice365Service();
    private final UUID notificationId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();

    @BeforeEach
    void resetStubs() {
        WireMockSupport.reset();
    }

    @Nested
    class WhenOffice365AcceptsTheEmail {

        @Test
        void should_post_the_legacy_request_with_the_subscription_key_and_the_function_key() {
            office365.sendEmailWillReturnSuccess(templateId.toString(), notificationId.toString());

            client(Office365StubService.endpoint()).send(request().build());

            office365.sendEmailRequestMatches(load(
                    "fixtures/office365/expected-send-email-request-with-material-document.json", of(
                            "templateId", templateId.toString(),
                            "notificationId", notificationId.toString(),
                            "filename", "d20.pdf")), DOCUMENT);
        }

        @Test
        void should_return_a_result_already_sent_with_the_subject_and_the_text_of_the_template_body() {
            office365.sendEmailWillReturnSuccess(templateId.toString(), notificationId.toString());

            final SendResult result = client(Office365StubService.endpoint()).send(request().build());

            assertThat(result.sentOnSubmit()).isTrue();
            assertThat(result.reference()).isEqualTo(notificationId.toString());
            assertThat(result.emailSubject()).isEqualTo("Your document is ready");
            assertThat(result.emailBody()).isEqualTo("Please find the document attached.");
        }

    }

    @Nested
    class WhenOffice365DoesNotAcceptTheEmail {

        @ParameterizedTest
        @ValueSource(ints = {400, 413})
        void should_fail_permanently_with_the_status_and_the_response_when_office365_rejects_the_request(final int status) {
            office365.sendEmailWillAnswer(status, "Invalid request payload\n");

            assertThatThrownBy(() -> client(Office365StubService.endpoint()).send(request().build()))
                    .isInstanceOf(Office365Exception.class)
                    .hasMessage("Office 365 responded with 'Invalid request payload'")
                    .hasFieldOrPropertyWithValue("httpStatus", status)
                    .hasFieldOrPropertyWithValue("permanent", true);
        }

        @ParameterizedTest
        @ValueSource(ints = {401, 403, 404, 500, 503})
        void should_fail_retryably_with_the_status_for_any_other_answer(final int status) {
            office365.sendEmailWillAnswer(status, "error");

            assertThatThrownBy(() -> client(Office365StubService.endpoint()).send(request().build()))
                    .isInstanceOf(Office365Exception.class)
                    .hasFieldOrPropertyWithValue("httpStatus", status)
                    .hasFieldOrPropertyWithValue("permanent", false);
        }

        @Test
        void should_fail_retryably_without_a_status_when_office365_does_not_answer_in_time() {
            office365.sendEmailWillAnswerAfter(Duration.ofSeconds(3));

            assertThatThrownBy(() -> client(Office365StubService.endpoint(), Duration.ofSeconds(1)).send(request().build()))
                    .isInstanceOf(Office365Exception.class)
                    .hasMessage("Office 365 did not answer in time")
                    .hasFieldOrPropertyWithValue("httpStatus", null)
                    .hasFieldOrPropertyWithValue("permanent", false);
        }

        @Test
        void should_fail_retryably_without_a_status_or_the_endpoint_when_office365_cannot_be_reached() {
            assertThatThrownBy(() -> client("http://localhost:1/api/Generate-Notification-Email?code=secret-key")
                    .send(request().build()))
                    .isInstanceOf(Office365Exception.class)
                    .hasMessage("Office 365 could not be reached")
                    .hasFieldOrPropertyWithValue("httpStatus", null)
                    .hasFieldOrPropertyWithValue("permanent", false);
        }
    }

    private SendEmailRequest.SendEmailRequestBuilder request() {
        return aSendEmailRequest()
                .notificationId(notificationId)
                .templateId(templateId)
                .emailAddress("reports@example.com")
                .personalisation(Map.of("recipient_name", "Alex Morgan"))
                .attachment(DOCUMENT)
                .attachmentFilename("d20.pdf");
    }

    private static Office365Client client(final String endpoint) {
        return client(endpoint, Duration.ofSeconds(300));
    }

    private static Office365Client client(final String endpoint, final Duration requestTimeout) {
        final Office365Settings settings = Office365Settings.from(new Office365Properties(
                endpoint, Office365StubService.SUBSCRIPTION_KEY, Duration.ofSeconds(30), requestTimeout));
        return new Office365Client(
                new Office365Config().office365HttpClient(settings),
                settings,
                new Office365RequestBodyFactory(JsonMapper.builder().build()),
                new Office365ResponseReader(JsonMapper.builder().build()));
    }
}
