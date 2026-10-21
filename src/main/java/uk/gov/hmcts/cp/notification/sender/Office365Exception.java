package uk.gov.hmcts.cp.notification.sender;

/**
 * The Office 365 route did not accept the email. {@code httpStatus} is {@code null} when no HTTP response was
 * received (I/O error or interrupt) or no call was made. Classification follows legacy {@code FailureSelector}:
 * only {@code 400} and {@code 413} are permanent; everything else is retried. A request that cannot be built
 * ({@link #unsendable}) is also permanent.
 */
public class Office365Exception extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private static final int BAD_REQUEST = 400;
    private static final int REQUEST_ENTITY_TOO_LARGE = 413;

    private final Integer httpStatus;
    private final boolean permanent;

    public Office365Exception(final Integer httpStatus, final String message, final Throwable cause) {
        this(httpStatus, message, cause,
                httpStatus != null && (httpStatus == BAD_REQUEST || httpStatus == REQUEST_ENTITY_TOO_LARGE));
    }

    private Office365Exception(
            final Integer httpStatus, final String message, final Throwable cause, final boolean permanent) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.permanent = permanent;
    }

    /**
     * The request could not be built, so no call was made and a retry would fail the same way.
     */
    public static Office365Exception unsendable(final String message, final Throwable cause) {
        return new Office365Exception(null, message, cause, true);
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
