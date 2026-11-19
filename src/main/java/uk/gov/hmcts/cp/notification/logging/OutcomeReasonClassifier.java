package uk.gov.hmcts.cp.notification.logging;

import static java.util.Optional.of;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.UNREACHABLE;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_ACCESS_REFUSED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_BAD_LINK;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_NOT_FOUND;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_TOO_LARGE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_UNAVAILABLE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_FAILED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_UNREACHABLE;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Works out the {@link OutcomeReason} the requesting team is alerted with when a call fails a notification
 * (PEG-3718, PEG-3719). The reasons that need no working out (the provider reported the notification undeliverable,
 * or had not finished with it) are written where they happen.
 */
@Component
public class OutcomeReasonClassifier {
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;

    /**
     * An attachment that can never be downloaded: not found (404), access refused (401 or 403), too large (413).
     * Anything else, including a link refused before any call was made (no status code), is a link the gateway
     * can't use.
     */
    public OutcomeReason attachmentFailed(final Optional<Integer> statusCode) {
        final Optional<OutcomeReason> outcomeReason = statusCode.map(this::attachmentFailureOf);
        return outcomeReason.orElse(ATTACHMENT_BAD_LINK);
    }

    /**
     * A provider call that failed and is not retried. Without an HTTP response, the {@link FailureReason} says
     * whether the provider could not be reached or did not respond, which is not the same as rejecting the
     * notification: it may even have sent it. With an HTTP response, or no known reason, the provider rejected it.
     */
    public OutcomeReason providerFailed(final Optional<FailureReason> failureReason) {
        return providerFailureOf(failureReason, PROVIDER_REJECTED);
    }

    /** A call that kept failing until the task's retries ran out. */
    public OutcomeReason retriesExhausted(final Operation operation, final Optional<FailureReason> failureReason) {
        final OutcomeReason outcomeReason;
        if (operation == DOWNLOAD) {
            outcomeReason = ATTACHMENT_UNAVAILABLE;
        } else {
            outcomeReason = providerFailureOf(failureReason, PROVIDER_FAILED);
        }
        return outcomeReason;
    }

    private OutcomeReason providerFailureOf(
            final Optional<FailureReason> failureReason, final OutcomeReason otherwise) {
        final OutcomeReason outcomeReason;
        if (failureReason.equals(of(UNREACHABLE))) {
            outcomeReason = PROVIDER_UNREACHABLE;
        } else if (failureReason.equals(of(NO_RESPONSE))) {
            outcomeReason = PROVIDER_NO_RESPONSE;
        } else {
            outcomeReason = otherwise;
        }
        return outcomeReason;
    }

    private OutcomeReason attachmentFailureOf(final int statusCode) {
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
