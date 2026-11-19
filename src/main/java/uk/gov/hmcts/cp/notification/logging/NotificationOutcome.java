package uk.gov.hmcts.cp.notification.logging;

/**
 * How a notification ended badly: rejected before anything was recorded, or recorded and then failed. Logged as the
 * {@link LogField#NOTIFICATION_OUTCOME} field, so that an alert can tell the team that owns the reply queue
 * (PEG-3718, PEG-3719). The alert queries read these values, so changing one breaks the alerts.
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
