package uk.gov.hmcts.cp.notification.blob;

import java.util.Optional;

/**
 * An attachment could not be downloaded from blob storage, but a retry may succeed: blob storage answered with a
 * status that is retried, or the blob client failed without an answer (for example a network failure, which the
 * cause says).
 */
public class TransientBlobException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient Optional<Integer> statusCode;

    public TransientBlobException(final String message, final Optional<Integer> statusCode, final Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** The status blob storage answered with; empty when there was no answer. */
    public Optional<Integer> getStatusCode() {
        return statusCode;
    }
}
