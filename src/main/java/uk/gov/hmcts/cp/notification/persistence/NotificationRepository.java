package uk.gov.hmcts.cp.notification.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    // Atomic idempotent claim: returns 1 for the first delivery of a notificationId, 0 for duplicates.
    @Modifying
    @Query(value = "insert into notification "
            + "(notification_id, notification_type, status, send_to_address, client_context, "
            + "result_queue, created_at, updated_at) "
            + "values (:id, :type, :status, :sendToAddress, :clientContext, "
            + ":resultQueue, :createdAt, :updatedAt) "
            + "on conflict (notification_id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("type") String type,
                       @Param("status") String status,
                       @Param("sendToAddress") String sendToAddress,
                       @Param("clientContext") String clientContext,
                       @Param("resultQueue") String resultQueue,
                       @Param("createdAt") OffsetDateTime createdAt,
                       @Param("updatedAt") OffsetDateTime updatedAt);
}
