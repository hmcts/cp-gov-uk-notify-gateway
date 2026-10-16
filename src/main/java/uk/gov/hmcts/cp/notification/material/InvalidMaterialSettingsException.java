package uk.gov.hmcts.cp.notification.material;

/**
 * A mandatory Material setting is missing or malformed, so the application must not start. The message
 * names the setting and its environment variable, never the configured value.
 */
public class InvalidMaterialSettingsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidMaterialSettingsException(final String message) {
        super(message);
    }
}
