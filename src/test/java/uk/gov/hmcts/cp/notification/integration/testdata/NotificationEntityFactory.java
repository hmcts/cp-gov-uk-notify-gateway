package uk.gov.hmcts.cp.notification.integration.testdata;

import uk.gov.hmcts.cp.notification.persistence.NotificationEntity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public final class NotificationEntityFactory {

    private NotificationEntityFactory() {
    }

    public static NotificationEntity.NotificationEntityBuilder aNotificationEntity() {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return NotificationEntity.builder()
                .notificationId(UUID.randomUUID())
                .notificationType("EMAIL")
                .status("QUEUED")
                .sendToAddress("recipient@example.com")
                .createdAt(now)
                .updatedAt(now);
    }

    public static NotificationEntity.NotificationEntityBuilder aLetterNotificationEntity() {
        return aNotificationEntity()
                .notificationType("LETTER")
                .sendToAddress(null)
                .letterUrl(SendLetterCommandFactory.LETTER_URL);
    }
}
