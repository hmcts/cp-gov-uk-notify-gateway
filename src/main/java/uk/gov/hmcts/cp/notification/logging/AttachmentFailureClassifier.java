package uk.gov.hmcts.cp.notification.logging;

import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_ACCESS_REFUSED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_BAD_LINK;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_NOT_FOUND;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_TOO_LARGE;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Works out the {@link OutcomeReason} for an attachment that can never be downloaded, from the status code of the
 * failure: not found (404), access refused (401 or 403), too large (413). Anything else, including a link refused
 * before any call was made (no status code), is a link the gateway can't use (PEG-3719).
 */
@Component
public class AttachmentFailureClassifier {
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;

    public OutcomeReason classify(final Optional<Integer> statusCode) {
        final Optional<OutcomeReason> outcomeReason = statusCode.map(this::outcomeReasonOf);
        return outcomeReason.orElse(ATTACHMENT_BAD_LINK);
    }

    private OutcomeReason outcomeReasonOf(final int statusCode) {
        final OutcomeReason outcomeReason;
        if (statusCode == HTTP_NOT_FOUND) {
            outcomeReason = ATTACHMENT_NOT_FOUND;
        } else if (statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN) {
            outcomeReason = ATTACHMENT_ACCESS_REFUSED;
        } else if (statusCode == HTTP_PAYLOAD_TOO_LARGE) {
            outcomeReason = ATTACHMENT_TOO_LARGE;
        } else {
            outcomeReason = ATTACHMENT_BAD_LINK;
        }
        return outcomeReason;
    }
}
