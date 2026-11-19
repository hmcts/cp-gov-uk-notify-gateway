package uk.gov.hmcts.cp.notification.logging;

/**
 * How a call to a {@link Dependency} ended. Logged as the {@link LogField#OUTCOME} field, so that an alert can count
 * failures apart from successful calls (PEG-3720). The alert queries read these values, so changing one breaks the
 * alerts.
 */
public enum CallOutcome {
    SUCCEEDED("succeeded"),
    TRANSIENT_FAILURE("transient-failure"),
    PERMANENT_FAILURE("permanent-failure");

    private final String fieldValue;

    CallOutcome(final String fieldValue) {
        this.fieldValue = fieldValue;
    }

    public String getFieldValue() {
        return fieldValue;
    }
}
