package uk.gov.hmcts.cp.notification.material;

/**
 * A Material attachment could not be obtained: the link was refused, Material did not return a usable
 * document link, or the document could not be downloaded from it. Carries the HTTP status when there was a
 * response, otherwise {@code null}. The message never contains a link, host or credential.
 */
public class MaterialAttachmentException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient Integer statusCode;

    public MaterialAttachmentException(final String message) {
        this(message, null, null);
    }

    public MaterialAttachmentException(final String message, final Integer statusCode) {
        this(message, statusCode, null);
    }

    public MaterialAttachmentException(final String message, final Throwable cause) {
        this(message, null, cause);
    }

    public MaterialAttachmentException(final String message, final Integer statusCode, final Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public Integer getStatusCode() {
        return statusCode;
    }
}
