package uk.gov.hmcts.cp.notification.logging;

/**
 * The JSON field names {@link DependencyCallLogger} writes for a {@link DependencyCall}. The Log Analytics alert
 * queries read these names, so changing one breaks the alerts (PEG-3720).
 */
public enum DependencyCallField {
    DEPENDENCY("dependency"),
    OPERATION("operation"),
    CHANNEL("channel"),
    OUTCOME("outcome"),
    STATUS_CODE("statusCode");

    private final String fieldName;

    DependencyCallField(final String fieldName) {
        this.fieldName = fieldName;
    }

    public String fieldName() {
        return fieldName;
    }
}
