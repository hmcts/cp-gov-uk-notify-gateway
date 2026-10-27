package uk.gov.hmcts.cp.notification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationEntity {
    @Id
    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "notification_type", nullable = false)
    private String notificationType;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "send_to_address")
    private String sendToAddress;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "client_context")
    private String clientContext;

    @Column(name = "result_queue")
    private String resultQueue;

    @Column(name = "material_url")
    private String materialUrl;

    @Column(name = "letter_url")
    private String letterUrl;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
