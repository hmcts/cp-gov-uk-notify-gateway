package uk.gov.hmcts.cp.notification.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import uk.gov.hmcts.cp.notification.integration.stubs.support.PostgresContainerSupport;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.NotificationEntityFactory.aNotificationEntity;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.MATERIAL_URL;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.LETTER_URL;

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

    @Test
    void saves_and_reads_back_a_notification() {
        final UUID id = UUID.randomUUID();

        notifications.saveAndFlush(aNotificationEntity()
                .notificationId(id)
                .sendToAddress("recipient@example.com")
                .clientContext("mi-reportdata")
                .resultQueue("ng-result-mi-reportdata")
                .materialUrl(MATERIAL_URL)
                .build());

        final NotificationEntity reloaded = notifications.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("QUEUED");
        assertThat(reloaded.getNotificationType()).isEqualTo("EMAIL");
        assertThat(reloaded.getSendToAddress()).isEqualTo("recipient@example.com");
        assertThat(reloaded.getClientContext()).isEqualTo("mi-reportdata");
        assertThat(reloaded.getResultQueue()).isEqualTo("ng-result-mi-reportdata");
        assertThat(reloaded.getMaterialUrl()).isEqualTo(MATERIAL_URL);
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
        assertThat(reloaded.getMaterialUrl()).isNull();
    }

    @Test
    void inserts_a_new_notification_once_and_ignores_a_duplicate() {
        final UUID id = UUID.randomUUID();
        final OffsetDateTime now = OffsetDateTime.now();

        final int first = notifications.insertIfAbsent(id, "EMAIL", "QUEUED", "recipient@example.com",
                "mi-reportdata", "ng-result-mi-reportdata", MATERIAL_URL, now, now);
        final int duplicate = notifications.insertIfAbsent(id, "EMAIL", "QUEUED", "other@example.com",
                "other", "other-queue", null, now, now);

        assertThat(first).isEqualTo(1);
        assertThat(duplicate).isZero();
        final NotificationEntity reloaded = notifications.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("QUEUED");
        assertThat(reloaded.getSendToAddress()).isEqualTo("recipient@example.com");
        assertThat(reloaded.getResultQueue()).isEqualTo("ng-result-mi-reportdata");
        assertThat(reloaded.getMaterialUrl()).isEqualTo(MATERIAL_URL);
    }

    @Test
    void inserts_a_new_letter_once_with_its_letter_url_and_no_recipient_and_ignores_a_duplicate() {
        final UUID id = UUID.randomUUID();
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        final int first = notifications.insertLetterIfAbsent(id, "LETTER", "QUEUED", "correspondence",
                "ng-result-correspondence", LETTER_URL, now, now);
        final int duplicate = notifications.insertLetterIfAbsent(id, "LETTER", "QUEUED", "other",
                "other-queue", "http://other.example/letter", now, now);

        assertThat(first).isEqualTo(1);
        assertThat(duplicate).isZero();
        final NotificationEntity reloaded = notifications.findById(id).orElseThrow();
        assertThat(reloaded.getNotificationType()).isEqualTo("LETTER");
        assertThat(reloaded.getStatus()).isEqualTo("QUEUED");
        assertThat(reloaded.getLetterUrl()).isEqualTo(LETTER_URL);
        assertThat(reloaded.getSendToAddress()).isNull();
        assertThat(reloaded.getMaterialUrl()).isNull();
        assertThat(reloaded.getClientContext()).isEqualTo("correspondence");
        assertThat(reloaded.getResultQueue()).isEqualTo("ng-result-correspondence");
    }

    @Test
    void does_not_insert_a_letter_over_an_existing_email_notification_with_the_same_id() {
        final UUID id = UUID.randomUUID();
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        notifications.insertIfAbsent(id, "EMAIL", "QUEUED", "recipient@example.com", null, null, null, now, now);

        final int inserted = notifications.insertLetterIfAbsent(id, "LETTER", "QUEUED", null, null, LETTER_URL, now, now);

        assertThat(inserted).isZero();
        final NotificationEntity reloaded = notifications.findById(id).orElseThrow();
        assertThat(reloaded.getNotificationType()).isEqualTo("EMAIL");
        assertThat(reloaded.getLetterUrl()).isNull();
    }
}
