package uk.gov.hmcts.cp.notification.logging;

/**
 * A notification that was rejected or failed, written to a log line as separate JSON fields by
 * {@link NotificationOutcomeLogger}. {@code replyQueue} is the queue the requesting service named for its results,
 * which identifies the team to alert (PEG-3718, PEG-3719). Never carries a recipient's details or the request's
 * content.
 */
public record NotificationOutcomeEvent(
        NotificationOutcome notificationOutcome,
        OutcomeReason outcomeReason,
        Channel channel,
        String replyQueue) {
}
