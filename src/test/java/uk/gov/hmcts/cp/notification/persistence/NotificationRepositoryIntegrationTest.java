package uk.gov.hmcts.cp.notification.persistence;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import uk.gov.hmcts.cp.notification.integration.stubs.support.PostgresContainerSupport;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.NotificationEntityFactory.aNotificationEntity;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class NotificationRepositoryIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(final DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/taskmanager");
    }

    @Autowired
    private NotificationRepository notifications;

    @Nested
    class Persistence {

        @Test
        void saves_and_reads_back_a_notification() {
            final UUID id = UUID.randomUUID();

            notifications.saveAndFlush(aNotificationEntity()
                    .notificationId(id)
                    .sendToAddress("recipient@example.com")
                    .clientContext("mi-reportdata")
                    .resultQueue("ng-result-mi-reportdata")
                    .build());

            final NotificationEntity reloaded = notifications.findById(id).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo("QUEUED");
            assertThat(reloaded.getNotificationType()).isEqualTo("EMAIL");
            assertThat(reloaded.getSendToAddress()).isEqualTo("recipient@example.com");
            assertThat(reloaded.getClientContext()).isEqualTo("mi-reportdata");
            assertThat(reloaded.getResultQueue()).isEqualTo("ng-result-mi-reportdata");
        }

        @Test
        void persists_a_notification_with_only_the_required_fields() {
            final UUID id = UUID.randomUUID();

            notifications.saveAndFlush(aNotificationEntity()
                    .notificationId(id)
                    .sendToAddress(null)
                    .build());

            final NotificationEntity reloaded = notifications.findById(id).orElseThrow();
            assertThat(reloaded.getSendToAddress()).isNull();
            assertThat(reloaded.getClientContext()).isNull();
            assertThat(reloaded.getResultQueue()).isNull();
        }

        @Test
        void inserts_a_new_notification_once_and_ignores_a_duplicate() {
            final UUID id = UUID.randomUUID();
            final OffsetDateTime now = OffsetDateTime.now();

            final int first = notifications.insertIfAbsent(id, "EMAIL", "QUEUED", "recipient@example.com",
                    "mi-reportdata", "ng-result-mi-reportdata", now, now);
            final int duplicate = notifications.insertIfAbsent(id, "EMAIL", "QUEUED", "other@example.com",
                    "other", "other-queue", now, now);

            assertThat(first).isEqualTo(1);
            assertThat(duplicate).isZero();
            final NotificationEntity reloaded = notifications.findById(id).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo("QUEUED");
            assertThat(reloaded.getSendToAddress()).isEqualTo("recipient@example.com");
            assertThat(reloaded.getResultQueue()).isEqualTo("ng-result-mi-reportdata");
        }
    }

    @Nested
    class Search {

        @Test
        void returns_only_notifications_matching_the_status_and_created_range() {
            final UUID inRangeFailed = UUID.randomUUID();
            notifications.saveAndFlush(aNotificationEntity()
                    .notificationId(inRangeFailed)
                    .status("FAILED")
                    .createdAt(OffsetDateTime.parse("2026-07-01T09:00:00Z"))
                    .updatedAt(OffsetDateTime.parse("2026-07-01T09:00:00Z"))
                    .build());
            notifications.saveAndFlush(aNotificationEntity()
                    .notificationId(UUID.randomUUID())
                    .status("SENT")
                    .createdAt(OffsetDateTime.parse("2026-07-01T09:00:00Z"))
                    .updatedAt(OffsetDateTime.parse("2026-07-01T09:00:00Z"))
                    .build());
            notifications.saveAndFlush(aNotificationEntity()
                    .notificationId(UUID.randomUUID())
                    .status("FAILED")
                    .createdAt(OffsetDateTime.parse("2026-06-01T09:00:00Z"))
                    .updatedAt(OffsetDateTime.parse("2026-06-01T09:00:00Z"))
                    .build());

            final Page<NotificationEntity> results = notifications.search(
                    "FAILED",
                    OffsetDateTime.parse("2026-06-15T00:00:00Z"),
                    OffsetDateTime.parse("2026-07-15T00:00:00Z"),
                    PageRequest.of(0, 10));

            assertThat(results.getContent()).singleElement()
                    .satisfies(match -> assertThat(match.getNotificationId()).isEqualTo(inRangeFailed));
        }
    }
}
