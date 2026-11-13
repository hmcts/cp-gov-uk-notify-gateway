package uk.gov.hmcts.cp.notification.sender;

import uk.gov.hmcts.cp.notification.logging.FailureReason;
import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Decides whether a {@link GovNotifyException} is worth retrying. {@code 400}, {@code 404} and {@code 413} are
 * permanent. Status {@code 0} is the Gov.Notify client's "no HTTP response": temporary when Gov.Notify could not be
 * reached or did not respond (an I/O failure among the causes), otherwise permanent (for example a request the
 * client could not build). Every other status is temporary.
 */
@Component
public class GovNotifyFailureClassifier {
    private static final int BAD_REQUEST = 400;
    private static final int NOT_FOUND = 404;
    private static final int REQUEST_ENTITY_TOO_LARGE = 413;
    private static final int NON_HTTP = 0;

    private final FailureReasonClassifier failureReasonClassifier;

    public GovNotifyFailureClassifier(final FailureReasonClassifier failureReasonClassifier) {
        this.failureReasonClassifier = failureReasonClassifier;
    }

    public boolean isTemporary(final GovNotifyException govNotifyException) {
        final int httpStatus = govNotifyException.getHttpStatus();
        final boolean temporary;
        if (httpStatus == BAD_REQUEST || httpStatus == NOT_FOUND || httpStatus == REQUEST_ENTITY_TOO_LARGE) {
            temporary = false;
        } else if (httpStatus == NON_HTTP) {
            final Optional<FailureReason> failureReason = failureReasonClassifier.classify(govNotifyException);
            temporary = failureReason.isPresent();
        } else {
            temporary = true;
        }
        return temporary;
    }
}
