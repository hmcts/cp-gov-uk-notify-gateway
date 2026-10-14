package uk.gov.hmcts.cp.notification.material;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class MaterialSettingsTest {
    private static final String SYSTEM_USER_ID = "7c1e3f5a-2b4d-4c6e-8f0a-1b2c3d4e5f60";
    private static final String BASE_URL = "http://material-service.example.internal/material-service";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MaterialConfig.class);

    @Nested
    class WhenTheSystemUserIsNotUsable {

        @Test
        void should_fail_to_start_when_the_system_user_is_missing() {
            contextRunner
                    .withPropertyValues("cp.notification.material.base-url=" + BASE_URL)
                    .run(context -> assertThat(context).getFailure()
                            .rootCause().hasMessageContaining("CP_NG_SYSTEM_USER_ID"));
        }

        @Test
        void should_fail_to_start_when_the_system_user_is_blank() {
            contextRunner
                    .withPropertyValues(
                            "cp.notification.material.system-user-id=  ",
                            "cp.notification.material.base-url=" + BASE_URL)
                    .run(context -> assertThat(context).getFailure()
                            .rootCause().hasMessageContaining("CP_NG_SYSTEM_USER_ID"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"not-a-uuid", "1-2-3-4-5", "7c1e3f5a2b4d4c6e8f0a1b2c3d4e5f60"})
        void should_fail_to_start_without_revealing_the_value_when_the_system_user_is_not_a_uuid(final String value) {
            contextRunner
                    .withPropertyValues(
                            "cp.notification.material.system-user-id=" + value,
                            "cp.notification.material.base-url=" + BASE_URL)
                    .run(context -> assertThat(context).getFailure()
                            .rootCause()
                            .hasMessageContaining("CP_NG_SYSTEM_USER_ID")
                            .satisfies(cause -> assertThat(cause.getMessage()).doesNotContain(value)));
        }
    }

    @Nested
    class WhenTheBaseUrlIsNotUsable {

        @Test
        void should_fail_to_start_when_the_base_url_is_missing() {
            contextRunner
                    .withPropertyValues("cp.notification.material.system-user-id=" + SYSTEM_USER_ID)
                    .run(context -> assertThat(context).getFailure()
                            .rootCause().hasMessageContaining("CP_NG_MATERIAL_BASE_URL"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"material-service/material-service", "ftp://material.example.internal/material",
                "file:///etc/passwd", "http:///no-host", "http://bad host/x"})
        void should_fail_to_start_without_revealing_the_value_when_the_base_url_is_not_an_http_url(final String value) {
            contextRunner
                    .withPropertyValues(
                            "cp.notification.material.system-user-id=" + SYSTEM_USER_ID,
                            "cp.notification.material.base-url=" + value)
                    .run(context -> assertThat(context).getFailure()
                            .rootCause()
                            .hasMessageContaining("CP_NG_MATERIAL_BASE_URL")
                            .satisfies(cause -> assertThat(cause.getMessage()).doesNotContain(value)));
        }
    }

    @Nested
    class WhenBothAreValid {

        @Test
        void should_start_with_the_default_timeouts_and_without_logging_either_value(final CapturedOutput output) {
            contextRunner
                    .withPropertyValues(
                            "cp.notification.material.system-user-id=" + SYSTEM_USER_ID,
                            "cp.notification.material.base-url=" + BASE_URL)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        final MaterialSettings settings = context.getBean(MaterialSettings.class);
                        assertThat(settings.systemUserId()).isEqualTo(UUID.fromString(SYSTEM_USER_ID));
                        assertThat(settings.baseUrl()).isEqualTo(URI.create(BASE_URL));
                        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
                        assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(35));
                        assertThat(settings.sasConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
                        assertThat(settings.sasReadTimeout()).isEqualTo(Duration.ofSeconds(30));
                        assertThat(settings.toString()).doesNotContain(SYSTEM_USER_ID).doesNotContain(BASE_URL);
                        assertThat(context.getBean(MaterialProperties.class).toString())
                                .doesNotContain(SYSTEM_USER_ID).doesNotContain(BASE_URL);
                    });

            assertThat(output.getAll()).doesNotContain(SYSTEM_USER_ID).doesNotContain(BASE_URL);
        }

        @Test
        void should_bind_timeout_overrides() {
            contextRunner
                    .withPropertyValues(
                            "cp.notification.material.system-user-id=" + SYSTEM_USER_ID,
                            "cp.notification.material.base-url=" + BASE_URL,
                            "cp.notification.material.connect-timeout=2s",
                            "cp.notification.material.read-timeout=60s",
                            "cp.notification.material.sas-connect-timeout=3s",
                            "cp.notification.material.sas-read-timeout=45s")
                    .run(context -> {
                        final MaterialSettings settings = context.getBean(MaterialSettings.class);
                        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                        assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(60));
                        assertThat(settings.sasConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
                        assertThat(settings.sasReadTimeout()).isEqualTo(Duration.ofSeconds(45));
                    });
        }
    }
}
