package uk.gov.hmcts.cp.notification.logging;

/**
 * Why a notification was rejected or failed. Logged as the {@code outcomeReason} field of a
 * {@link NotificationOutcomeEvent}, so that an alert can count each reason for each reply queue (PEG-3718,
 * PEG-3719). The alert queries read these values, so changing one breaks the alerts.
 */
public enum OutcomeReason {
    UNREADABLE_REQUEST("unreadable-request", "the request could not be read"),
    INVALID_REQUEST("invalid-request", "the request failed validation"),
    ATTACHMENT_NOT_FOUND("attachment-not-found", "the attachment was not found"),
    ATTACHMENT_ACCESS_REFUSED("attachment-access-refused", "access to the attachment was refused"),
    ATTACHMENT_TOO_LARGE("attachment-too-large", "the attachment is too large"),
    ATTACHMENT_BAD_LINK("attachment-bad-link", "the attachment link is not one the gateway can use"),
    ATTACHMENT_UNAVAILABLE("attachment-unavailable",
            "the attachment could not be downloaded within the retry window"),
    PROVIDER_REJECTED("provider-rejected", "the provider rejected the notification"),
    PROVIDER_UNREACHABLE("provider-unreachable", "the provider could not be reached within the retry window"),
    PROVIDER_NO_RESPONSE("provider-no-response", "the provider did not respond within the retry window"),
    PROVIDER_FAILED("provider-failed", "the provider kept failing until the retry window ended"),
    DELIVERY_FAILED("delivery-failed", "the provider reported that the notification could not be delivered"),
    STATUS_UNKNOWN("status-unknown", "the provider had not finished with the notification when the retry window ended");

    private final String fieldValue;
    private final String reasonDescription;

    OutcomeReason(final String fieldValue, final String reasonDescription) {
        this.fieldValue = fieldValue;
        this.reasonDescription = reasonDescription;
    }

    public String getFieldValue() {
        return fieldValue;
    }

    public String getDescription() {
        return reasonDescription;
    }
}
