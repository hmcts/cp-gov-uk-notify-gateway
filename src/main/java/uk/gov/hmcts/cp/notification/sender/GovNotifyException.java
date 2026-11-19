package uk.gov.hmcts.cp.notification.sender;

import static java.util.Optional.empty;
import static java.util.Optional.of;

import java.util.Optional;

public class GovNotifyException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private static final int NO_HTTP_RESPONSE = 0;

    private final int httpStatus;

    public GovNotifyException(final int httpStatus, final String message, final Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** The status Gov.Notify answered with; empty when the Gov.Notify client got no HTTP response (status 0). */
    public Optional<Integer> getHttpStatusCode() {
        final Optional<Integer> httpStatusCode;
        if (httpStatus == NO_HTTP_RESPONSE) {
            httpStatusCode = empty();
        } else {
            httpStatusCode = of(httpStatus);
        }
        return httpStatusCode;
    }
}
