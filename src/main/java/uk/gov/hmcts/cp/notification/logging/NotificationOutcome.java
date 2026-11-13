package uk.gov.hmcts.cp.notification.logging;

/**
 * How a notification ended badly: rejected before anything was recorded, or recorded and then failed. Logged as the
 * {@code notificationOutcome} field of a {@link NotificationOutcomeEvent}, so that an alert can tell the owning team
 * of the reply queue (PEG-3718).
 */
public enum NotificationOutcome {
    REJECTED("rejected"),
    FAILED("failed");

    private final String fieldValue;

    NotificationOutcome(final String fieldValue) {
        this.fieldValue = fieldValue;
    }

    public String getFieldValue() {
        return fieldValue;
    }
}
