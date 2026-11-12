package uk.gov.hmcts.cp.notification.logging;

/**
 * What the gateway asked a {@link Dependency} to do. Logged as the {@code operation} field of a
 * {@link DependencyCall}, so that an alert can count send attempts apart from status checks and downloads (PEG-3720).
 */
public enum Operation {
    SEND("send"),
    STATUS_CHECK("status-check"),
    DOWNLOAD("download");

    private final String fieldValue;

    Operation(final String fieldValue) {
        this.fieldValue = fieldValue;
    }

    public String getFieldValue() {
        return fieldValue;
    }
}
