package uk.gov.hmcts.cp.notification.material;

/**
 * A Material attachment could not be obtained: the link was refused, Material did not return a usable
 * document link, or the document could not be downloaded from it. Carries the HTTP status when there was a
 * response, otherwise {@code null}, and whether a retry may succeed: a permanent failure is failed at once,
 * a retryable one is retried (each retry asks Material for a fresh link) until the retry window ends. The
 * message never contains a link, host or credential.
 */
public final class MaterialAttachmentException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient Integer statusCode;
    private final boolean retryable;

    private MaterialAttachmentException(
            final String message, final Integer statusCode, final boolean retryable, final Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public static MaterialAttachmentException permanent(final String message, final Integer statusCode) {
        return new MaterialAttachmentException(message, statusCode, false, null);
    }

    public static MaterialAttachmentException retryable(final String message, final Integer statusCode) {
        return new MaterialAttachmentException(message, statusCode, true, null);
    }

    public static MaterialAttachmentException retryable(
            final String message, final Integer statusCode, final Throwable cause) {
        return new MaterialAttachmentException(message, statusCode, true, cause);
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
