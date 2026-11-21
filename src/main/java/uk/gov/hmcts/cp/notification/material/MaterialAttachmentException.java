package uk.gov.hmcts.cp.notification.material;

import uk.gov.hmcts.cp.notification.logging.Dependency;

/**
 * A Material attachment could not be obtained: the link was refused, Material did not return a usable
 * document link, or the document could not be downloaded from it. Carries the {@link Dependency} that failed (Material
 * for the link, blob storage for the download from the SAS link), the HTTP status when there was a response,
 * otherwise {@code null}, and whether a retry may succeed: a permanent failure is failed at once, a retryable one is
 * retried (each retry asks Material for a fresh link) until the retry window ends. The message never contains a
 * link, host or credential.
 */
public final class MaterialAttachmentException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Dependency dependency;
    private final transient Integer statusCode;
    private final boolean retryAllowed;

    private MaterialAttachmentException(
            final Dependency dependency,
            final String message,
            final Integer statusCode,
            final boolean retryable,
            final Throwable cause) {
        super(message, cause);
        this.dependency = dependency;
        this.statusCode = statusCode;
        this.retryAllowed = retryable;
    }

    public static MaterialAttachmentException permanent(
            final Dependency dependency, final String message, final Integer statusCode) {
        return new MaterialAttachmentException(dependency, message, statusCode, false, null);
    }

    public static MaterialAttachmentException retryable(
            final Dependency dependency, final String message, final Integer statusCode) {
        return new MaterialAttachmentException(dependency, message, statusCode, true, null);
    }

    public static MaterialAttachmentException retryable(
            final Dependency dependency, final String message, final Integer statusCode, final Throwable cause) {
        return new MaterialAttachmentException(dependency, message, statusCode, true, cause);
    }

    public Dependency getDependency() {
        return dependency;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryAllowed;
    }
}
