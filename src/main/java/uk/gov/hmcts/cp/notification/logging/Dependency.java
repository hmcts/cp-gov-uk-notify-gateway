package uk.gov.hmcts.cp.notification.logging;

/**
 * A service the gateway calls to send a notification or fetch its attachment. Logged as the {@code dependency} field
 * of a {@link DependencyCall} so that alerts can count failures per dependency (PEG-3720).
 */
public enum Dependency {
    GOV_UK_NOTIFY("gov-uk-notify"),
    MICROSOFT_365("microsoft-365"),
    BLOB_STORAGE("blob-storage"),
    MATERIAL("material");

    private final String fieldValue;

    Dependency(final String fieldValue) {
        this.fieldValue = fieldValue;
    }

    public String fieldValue() {
        return fieldValue;
    }
}
