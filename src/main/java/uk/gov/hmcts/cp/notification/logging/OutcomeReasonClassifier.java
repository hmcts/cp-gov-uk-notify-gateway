package uk.gov.hmcts.cp.notification.logging;

import static java.util.Optional.of;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.UNREACHABLE;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_UNAVAILABLE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_FAILED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_UNREACHABLE;

import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.failure.DownloadStatusTable;

import java.util.Optional;

/**
 * Works out the {@link OutcomeReason} the requesting team is alerted with when a call fails a notification
 * (PEG-3718, PEG-3719). The reasons that need no working out (the provider reported the notification undeliverable,
 * or had not finished with it) are written where they happen.
 */
@Component
public class OutcomeReasonClassifier {
    private final DownloadStatusTable downloadStatusTable;

    public OutcomeReasonClassifier(final DownloadStatusTable downloadStatusTable) {
        this.downloadStatusTable = downloadStatusTable;
    }

    /** An attachment that can never be downloaded, by its status code, as {@link DownloadStatusTable} says. */
    public OutcomeReason attachmentFailed(final Optional<Integer> statusCode) {
        return downloadStatusTable.outcomeReasonFor(statusCode);
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
}
