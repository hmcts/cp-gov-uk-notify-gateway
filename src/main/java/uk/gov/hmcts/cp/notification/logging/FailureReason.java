package uk.gov.hmcts.cp.notification.logging;

/**
 * Why a call to a {@link Dependency} failed when the dependency gave no status code. Logged as the {@code reason}
 * field of a {@link DependencyCall}, so that an alert can tell an outage from other failures (PEG-3720).
 */
public enum FailureReason {
    UNREACHABLE("unreachable", "could not be reached"),
    NO_RESPONSE("no-response", "did not respond");

    private final String fieldValue;
    private final String reasonDescription;

    FailureReason(final String fieldValue, final String reasonDescription) {
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
