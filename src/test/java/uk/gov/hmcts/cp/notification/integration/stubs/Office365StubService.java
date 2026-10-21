package uk.gov.hmcts.cp.notification.integration.stubs;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import uk.gov.hmcts.cp.notification.integration.stubs.support.WireMockSupport;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.Base64.getDecoder;
import static java.util.Map.of;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.Fixtures.load;

public final class Office365StubService {
    public static final String SUBSCRIPTION_KEY = "cpngtest-office365-subscription-key";
    public static final String FUNCTION_KEY = "cpngtest-function-key";

    private static final String SEND_EMAIL_PATH = "/api/Generate-Notification-Email";
    private static final String CLIENT_ID = "notifyatt";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Office365StubService() {
    }

    public static Office365StubService anOffice365Service() {
        return new Office365StubService();
    }

    public static String endpoint() {
        return WireMockSupport.baseUrl() + SEND_EMAIL_PATH + "?code=" + FUNCTION_KEY + "&clientId=" + CLIENT_ID;
    }

    public static void registerProperties(final DynamicPropertyRegistry registry) {
        registry.add("cp.notification.office365.endpoint", Office365StubService::endpoint);
        registry.add("cp.notification.office365.subscription-key", () -> SUBSCRIPTION_KEY);
    }

    public Office365StubService sendEmailWillReturnSuccess(final String templateId, final String notificationId) {
        WireMockSupport.wiremockServer().stubFor(post(urlPathEqualTo(SEND_EMAIL_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(load("fixtures/office365/send-email-success.json",
                                of("templateId", templateId, "notificationId", notificationId)))));
        return this;
    }

    public Office365StubService sendEmailWillAnswer(final int status, final String body) {
        WireMockSupport.wiremockServer().stubFor(post(urlPathEqualTo(SEND_EMAIL_PATH))
                .willReturn(aResponse().withStatus(status).withBody(body)));
        return this;
    }

    public Office365StubService sendEmailWillAnswerAfter(final Duration delay) {
        WireMockSupport.wiremockServer().stubFor(post(urlPathEqualTo(SEND_EMAIL_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay((int) delay.toMillis())));
        return this;
    }

    public Office365StubService sendEmailRequestMatches(final String expectedRequestJson, final byte[] expectedAttachment) {
        final LoggedRequest request = theOnlySendEmailRequest();
        assertThat(request.getHeader("Content-Type")).as("JSON body").startsWith("application/json");
        assertThat(request.getHeader("Ocp-Apim-Subscription-Key")).as("APIM subscription key").isEqualTo(SUBSCRIPTION_KEY);
        assertThat(request.queryParameter("code").firstValue()).as("function key").isEqualTo(FUNCTION_KEY);
        assertThat(request.queryParameter("clientId").firstValue()).as("function key name").isEqualTo(CLIENT_ID);

        final String body = request.getBodyAsString();
        assertThatJson(body).as("send-email request payload matches the legacy contract").isEqualTo(expectedRequestJson);
        final String encodedAttachment = MAPPER.readTree(body).path("attachment").path("content").asString();
        assertThat(getDecoder().decode(encodedAttachment))
                .as("attachment bytes delivered to Office 365").isEqualTo(expectedAttachment);
        return this;
    }

    public Office365StubService emailWasSentOnce() {
        theOnlySendEmailRequest();
        return this;
    }

    public Office365StubService emailWasNotSent() {
        assertThat(WireMockSupport.wiremockServer().findAll(postRequestedFor(urlPathEqualTo(SEND_EMAIL_PATH))))
                .as("no send-email request should have reached Office 365")
                .isEmpty();
        return this;
    }

    private static LoggedRequest theOnlySendEmailRequest() {
        final List<LoggedRequest> requests = WireMockSupport.wiremockServer()
                .findAll(postRequestedFor(urlPathEqualTo(SEND_EMAIL_PATH)));
        assertThat(requests).as("exactly one send-email request reaches Office 365").hasSize(1);
        return requests.get(0);
    }
}
