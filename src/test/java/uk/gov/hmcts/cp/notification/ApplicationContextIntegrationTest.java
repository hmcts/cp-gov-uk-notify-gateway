package uk.gov.hmcts.cp.notification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import uk.gov.hmcts.cp.notification.integration.base.AbstractEndToEndIntegrationTest;
import uk.gov.hmcts.cp.notification.integration.repository.NotificationTestRepository;
import uk.gov.hmcts.cp.notification.integration.stubs.support.WireMockSupport;
import uk.gov.hmcts.cp.notification.material.MaterialSettings;
import uk.gov.hmcts.cp.notification.persistence.NotificationEntity;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.NotificationEntityFactory.aNotificationEntity;

class ApplicationContextIntegrationTest extends AbstractEndToEndIntegrationTest {
    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NotificationTestRepository notifications;

    @Test
    void the_application_context_loads() {
        assertThat(applicationContext.getBeanDefinitionCount()).isPositive();
    }

    @Test
    void the_application_starts_with_the_configured_material_settings_and_default_timeouts() {
        final MaterialSettings settings = applicationContext.getBean(MaterialSettings.class);

        assertThat(settings.systemUserId()).isEqualTo(UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100"));
        assertThat(settings.baseUrl()).isEqualTo(URI.create(WireMockSupport.baseUrl() + "/material-service"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(35));
        assertThat(settings.sasConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.sasReadTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Nested
    class ActuatorTests {
        @Test
        @SuppressWarnings("unchecked")
        void health_reports_up_with_liveness_and_readiness_groups() {
            final ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            final Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("status")).isEqualTo("UP");
            assertThat((List<String>) body.get("groups")).containsExactlyInAnyOrder("liveness", "readiness");
        }

        @Test
        @SuppressWarnings("unchecked")
        void info_exposes_build_and_git_metadata() {
            final ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/info", Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            final Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();

            final Map<String, Object> build = (Map<String, Object>) body.get("build");
            assertThat(build).isNotNull();
            assertThat(build.get("artifact")).isEqualTo("cp-gov-uk-notify-gateway");
            assertThat(build.get("name")).isEqualTo("cp-gov-uk-notify-gateway");
            assertThat(build.get("time")).isNotNull();
            assertThat(build.get("version")).isNotNull();

            final Map<String, Object> git = (Map<String, Object>) body.get("git");
            assertThat(git).isNotNull();
            assertThat(git.get("branch")).isNotNull();
            assertThat((Map<String, Object>) git.get("commit")).containsKeys("id", "time");
        }

        @ParameterizedTest
        @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
        @SuppressWarnings("unchecked")
        void probe_reports_up(final String probe) {
            final ResponseEntity<Map> response = restTemplate.getForEntity(probe, Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "UP");
        }
    }

    @Nested
    class RefusedRequests {

        @Test
        void a_path_outside_the_actuator_endpoints_is_forbidden() {
            final ResponseEntity<String> response = restTemplate.getForEntity("/anything-else", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    class RemovedNotificationQueries {

        @AfterEach
        void cleanUp() {
            notifications.deleteAll();
        }

        @Test
        void looking_up_a_recorded_notification_by_id_is_refused_without_returning_it() {
            final NotificationEntity recorded = notifications.save(aNotificationEntity()
                    .sendToAddress("recipient@example.com")
                    .build());

            final ResponseEntity<String> response = restTemplate.getForEntity(
                    "/notifications/{id}", String.class, recorded.getNotificationId());

            assertRefusedWithoutNotificationData(response, recorded);
        }

        @Test
        void searching_notifications_is_refused_without_returning_any() {
            final NotificationEntity recorded = notifications.save(aNotificationEntity()
                    .sendToAddress("recipient@example.com")
                    .build());

            final ResponseEntity<String> response = restTemplate.getForEntity(
                    "/notifications?status={status}", String.class, recorded.getStatus());

            assertRefusedWithoutNotificationData(response, recorded);
        }

        private void assertRefusedWithoutNotificationData(
                final ResponseEntity<String> response, final NotificationEntity recorded) {
            assertThat(response.getStatusCode().value()).isIn(401, 403, 404);
            assertThat(response.getBody())
                    .satisfiesAnyOf(
                            body -> assertThat(body).isNull(),
                            body -> assertThat(body)
                                    .doesNotContain(recorded.getNotificationId().toString())
                                    .doesNotContain(recorded.getSendToAddress()));
        }
    }
}
