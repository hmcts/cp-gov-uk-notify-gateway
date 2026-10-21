package uk.gov.hmcts.cp.notification.sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class Office365SettingsTest {
    private static final String ENDPOINT =
            "https://fa-test-notifyatt.example.net/api/Generate-Notification-Email?code=function-key&clientId=notifyatt";
    private static final String SUBSCRIPTION_KEY = "subscription-key-value";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Office365Config.class);

    @Test
    void should_start_with_the_endpoint_including_its_function_key_and_the_subscription_key() {
        contextRunner
                .withPropertyValues(
                        "cp.notification.office365.endpoint=" + ENDPOINT,
                        "cp.notification.office365.subscription-key=" + SUBSCRIPTION_KEY)
                .run(context -> {
                    final Office365Settings settings = context.getBean(Office365Settings.class);
                    assertThat(settings.endpoint()).isEqualTo(URI.create(ENDPOINT));
                    assertThat(settings.subscriptionKey()).isEqualTo(SUBSCRIPTION_KEY);
                    assertThat(settings.toString()).doesNotContain("function-key", SUBSCRIPTION_KEY);
                });
    }

    @Test
    void should_default_to_a_30s_connect_timeout_and_a_300s_request_timeout() {
        contextRunner
                .withPropertyValues(
                        "cp.notification.office365.endpoint=" + ENDPOINT,
                        "cp.notification.office365.subscription-key=" + SUBSCRIPTION_KEY)
                .run(context -> {
                    final Office365Settings settings = context.getBean(Office365Settings.class);
                    assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(300));
                });
    }

    @Test
    void should_fail_to_start_without_revealing_the_value_when_the_endpoint_is_still_xml_escaped() {
        contextRunner
                .withPropertyValues(
                        "cp.notification.office365.endpoint=" + ENDPOINT.replace("&", "&amp;"),
                        "cp.notification.office365.subscription-key=" + SUBSCRIPTION_KEY)
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("CP_NG_OFFICE365_ENDPOINT")
                        .hasMessageContaining("&amp;")
                        .hasMessageNotContaining("function-key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void should_fail_to_start_when_the_endpoint_is_missing(final String endpoint) {
        contextRunner
                .withPropertyValues(
                        "cp.notification.office365.endpoint=" + endpoint,
                        "cp.notification.office365.subscription-key=" + SUBSCRIPTION_KEY)
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("CP_NG_OFFICE365_ENDPOINT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fa-test-notifyatt.example.net/api?code=function-key", "ftp://example.net/api?code=function-key"})
    void should_fail_to_start_without_revealing_the_value_when_the_endpoint_is_not_an_http_url(final String endpoint) {
        contextRunner
                .withPropertyValues(
                        "cp.notification.office365.endpoint=" + endpoint,
                        "cp.notification.office365.subscription-key=" + SUBSCRIPTION_KEY)
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("CP_NG_OFFICE365_ENDPOINT")
                        .hasMessageNotContaining("function-key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void should_fail_to_start_when_the_subscription_key_is_missing(final String subscriptionKey) {
        contextRunner
                .withPropertyValues(
                        "cp.notification.office365.endpoint=" + ENDPOINT,
                        "cp.notification.office365.subscription-key=" + subscriptionKey)
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("CP_NG_OFFICE365_SUBSCRIPTION_KEY"));
    }
}
