package uk.gov.hmcts.cp.notification.logging;

/**
 * Whether a notification is an email or a letter. Logged as the {@link LogField#CHANNEL} field of a call to another
 * service, because GOV.UK Notify sends both (PEG-3720), and as {@link LogField#NOTIFICATION_CHANNEL}.
 */
public enum Channel {
    EMAIL("email"),
    LETTER("letter");

    private final String fieldValue;

    Channel(final String fieldValue) {
        this.fieldValue = fieldValue;
    }

    public String getFieldValue() {
        return fieldValue;
    }
}
