package uk.gov.hmcts.cp.notification.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import uk.gov.hmcts.cp.notification.persistence.NotificationEntity;
import uk.gov.hmcts.cp.notification.persistence.NotificationRepository;
import uk.gov.hmcts.cp.notification.result.NotificationFailedEvent;
import uk.gov.hmcts.cp.notification.result.NotificationSentEvent;
import uk.gov.hmcts.cp.notification.result.ResultEventPublisher;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationStatusService {
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_REQUEUED = "REQUEUED";

    private static final Logger LOG = LoggerFactory.getLogger(NotificationStatusService.class);

    private final NotificationRepository notificationRepository;
    private final Clock clock;
    private final ResultEventPublisher resultEventPublisher;

    @Transactional
    public void markSent(final UUID notificationId, final NotificationEmailDetails emailDetails) {
        recordSent(notificationId, emailDetails);
    }

    /**
     * Marks SENT in its own transaction, for {@code RecordSentTask}. A failure here then rolls back only this
     * update, not the task's transaction, so the task can record its retry instead of being rolled back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSentInNewTransaction(final UUID notificationId, final NotificationEmailDetails emailDetails) {
        recordSent(notificationId, emailDetails);
    }

    private void recordSent(final UUID notificationId, final NotificationEmailDetails emailDetails) {
        final NotificationEntity notification = getById(notificationId);
        notification.setStatus(STATUS_SENT);
        notification.setUpdatedAt(now());
        notificationRepository.save(notification);
        LOG.info("Notification {} marked SENT", notificationId);
        resultEventPublisher.publish(notification.getResultQueue(), sentEvent(notification)
                .sendToAddress(notification.getSendToAddress())
                .emailSubject(emailDetails.emailSubject())
                .emailBody(emailDetails.emailBody())
                .replyToAddress(emailDetails.replyToAddress())
                .build());
    }

    /**
     * Marks a letter SENT once Gov.Notify has received it. Legacy parity: the sent event carries
     * {@code completedAt} (the time it was recorded) and none of the email fields.
     */
    @Transactional
    public void markLetterSent(final UUID notificationId) {
        final NotificationEntity notification = getById(notificationId);
        notification.setStatus(STATUS_SENT);
        notification.setUpdatedAt(now());
        notificationRepository.save(notification);
        LOG.info("Letter notification {} marked SENT", notificationId);
        resultEventPublisher.publish(notification.getResultQueue(), sentEvent(notification)
                .completedAt(notification.getUpdatedAt())
                .build());
    }

    /**
     * Marks a letter REQUEUED when Gov.Notify failed its validation and it is being sent again (legacy
     * {@code letter-queued-for-resend}). Like legacy, any earlier error is cleared. No result event: the
     * originator hears only the final outcome.
     */
    @Transactional
    public void markRequeued(final UUID notificationId) {
        final NotificationEntity notification = getById(notificationId);
        notification.setStatus(STATUS_REQUEUED);
        notification.setStatusCode(null);
        notification.setErrorMessage(null);
        notification.setUpdatedAt(now());
        notificationRepository.save(notification);
        LOG.info("Letter notification {} marked REQUEUED", notificationId);
    }

    @Transactional
    public void markFailed(final UUID notificationId, final Integer statusCode, final String errorMessage) {
        final NotificationEntity notification = getById(notificationId);
        notification.setStatus(STATUS_FAILED);
        notification.setStatusCode(statusCode);
        notification.setErrorMessage(errorMessage);
        notification.setUpdatedAt(now());
        notificationRepository.save(notification);
        LOG.warn("Notification {} marked FAILED (statusCode={})", notificationId, statusCode);
        publishFailed(notification);
    }

    private static NotificationSentEvent.NotificationSentEventBuilder sentEvent(final NotificationEntity notification) {
        return NotificationSentEvent.builder()
                .notificationId(notification.getNotificationId())
                .sentTime(notification.getUpdatedAt())
                .clientContext(notification.getClientContext());
    }

    private void publishFailed(final NotificationEntity notification) {
        resultEventPublisher.publish(notification.getResultQueue(), NotificationFailedEvent.builder()
                .notificationId(notification.getNotificationId())
                .failedTime(notification.getUpdatedAt())
                .errorMessage(notification.getErrorMessage())
                .statusCode(notification.getStatusCode())
                .clientContext(notification.getClientContext())
                .build());
    }

    private NotificationEntity getById(final UUID notificationId) {
        return notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException(
                        "No notification row for id " + notificationId));
    }

    private OffsetDateTime now() {
        return clock.offsetDateTime();
    }
}
