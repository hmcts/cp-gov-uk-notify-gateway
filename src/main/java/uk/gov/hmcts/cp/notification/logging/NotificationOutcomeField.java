package uk.gov.hmcts.cp.notification.logging;

/**
 * The JSON field names {@link NotificationOutcomeLogger} writes for a {@link NotificationOutcomeEvent}. They differ
 * from the {@link DependencyCallField} names, so that the two kinds of log line never mix in a query. The Log
 * Analytics alert queries read these names, so changing one breaks the alerts (PEG-3718, PEG-3719).
 */
public enum NotificationOutcomeField {
    REPLY_QUEUE("replyQueue"),
    NOTIFICATION_OUTCOME("notificationOutcome"),
    OUTCOME_REASON("outcomeReason"),
    NOTIFICATION_CHANNEL("notificationChannel");

    private final String fieldName;

    NotificationOutcomeField(final String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }
}
