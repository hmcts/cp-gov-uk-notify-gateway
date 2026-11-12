package uk.gov.hmcts.cp.notification.logging;

/**
 * How a call to a {@link Dependency} ended. Logged as the {@code outcome} field of a {@link DependencyCall}.
 */
public enum CallOutcome {
    SUCCEEDED("succeeded"),
    TRANSIENT_FAILURE("transient-failure"),
    PERMANENT_FAILURE("permanent-failure");

    private final String fieldValue;

    CallOutcome(final String fieldValue) {
        this.fieldValue = fieldValue;
    }

    public String fieldValue() {
        return fieldValue;
    }
}
