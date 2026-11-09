package uk.gov.hmcts.cp.notification.logging;

/**
 * Text for a value that must not reach a log: {@code <redacted>} when it is set, {@code null} when it is not, so
 * a log still shows whether the value was there without showing what it was.
 */
public final class Redaction {

    private static final String MASK = "<redacted>";

    private Redaction() {
    }

    public static String redacted(final Object value) {
        return value == null ? "null" : MASK;
    }
}
