package uk.gov.hmcts.cp.notification.service;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.hmcts.cp.notification.logging.LogField.NOTIFICATION_CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.LogField.NOTIFICATION_OUTCOME;
import static uk.gov.hmcts.cp.notification.logging.LogField.OUTCOME_REASON;
import static uk.gov.hmcts.cp.notification.logging.LogField.REPLY_QUEUE;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import uk.gov.hmcts.cp.notification.logging.Channel;
import uk.gov.hmcts.cp.notification.logging.NotificationOutcome;
import uk.gov.hmcts.cp.notification.logging.OutcomeReason;
import uk.gov.hmcts.cp.notification.persistence.NotificationEntity;
import uk.gov.hmcts.cp.notification.persistence.NotificationRepository;
import uk.gov.hmcts.cp.notification.result.NotificationFailedEvent;
import uk.gov.hmcts.cp.notification.result.NotificationSentEvent;
import uk.gov.hmcts.cp.notification.result.ResultEventPublisher;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationStatusService {
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_REQUEUED = "REQUEUED";

    private static final Logger LOG = getLogger(NotificationStatusService.class);

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
     * Marks a letter SENT once Gov.Notify has received it. Legacy parity: the sent event carries none of the
     * email fields.
     */
    @Transactional
    public void markLetterSent(final UUID notificationId) {
        final NotificationEntity notification = getById(notificationId);
        notification.setStatus(STATUS_SENT);
        notification.setUpdatedAt(now());
        notificationRepository.save(notification);
        LOG.info("Letter notification {} marked SENT", notificationId);
        resultEventPublisher.publish(notification.getResultQueue(), sentEvent(notification).build());
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

    /**
     * Marks the notification FAILED, tells the originator, then logs it with the fields the requester alert counts for
     * the team that owns the reply queue (PEG-3718, PEG-3719). The line is written only once the event has been
     * published: if publishing fails, the transaction rolls back and the task runs again, and an earlier line would be
     * counted again on every attempt for a notification that is not FAILED.
     */
    @Transactional
    public void markFailed(final NotificationFailure notificationFailure) {
        final UUID notificationId = notificationFailure.notificationId();
        final Integer statusCode = notificationFailure.statusCode();
        final NotificationEntity notification = getById(notificationId);
        notification.setStatus(STATUS_FAILED);
        notification.setStatusCode(statusCode);
        notification.setErrorMessage(notificationFailure.errorMessage());
        notification.setUpdatedAt(now());
        notificationRepository.save(notification);
        final Channel channel = notificationFailure.channel();
        final String replyQueue = notification.getResultQueue();
        final OutcomeReason outcomeReason = notificationFailure.outcomeReason();
        publishFailed(notification);
        logFailed(notificationId, channel, replyQueue, statusCode, outcomeReason);
    }

    private void logFailed(
            final UUID notificationId,
            final Channel channel,
            final String replyQueue,
            final Integer statusCode,
            final OutcomeReason outcomeReason) {
        final Optional<Integer> possibleStatusCode = ofNullable(statusCode);
        final Optional<String> possibleStatusCodeText = possibleStatusCode.map(String::valueOf);
        final String statusCodeText = possibleStatusCodeText.orElse("none");
        final String outcomeReasonDescription = outcomeReason.getDescription();
        final String outcomeReasonName = outcomeReason.getFieldValue();
        final String channelName = channel.getFieldValue();
        final String message = format("Notification '%s' for reply queue '%s' marked FAILED (statusCode '%s'): %s",
                notificationId, replyQueue, statusCodeText, outcomeReasonDescription);
        LOG.atWarn()
                .addKeyValue(REPLY_QUEUE, replyQueue)
                .addKeyValue(NOTIFICATION_OUTCOME, NotificationOutcome.FAILED.getFieldValue())
                .addKeyValue(OUTCOME_REASON, outcomeReasonName)
                .addKeyValue(NOTIFICATION_CHANNEL, channelName)
                .log(message);
    }

    private static NotificationSentEvent.NotificationSentEventBuilder sentEvent(final NotificationEntity notification) {
        return NotificationSentEvent.builder()
                .notificationId(notification.getNotificationId())
                .sentTime(notification.getUpdatedAt())
                .completedAt(notification.getUpdatedAt())
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
