package uk.gov.hmcts.cp.notification.failure;

import static java.util.Map.entry;
import static uk.gov.hmcts.cp.notification.failure.DownloadSource.BLOB_ATTACHMENT;
import static uk.gov.hmcts.cp.notification.failure.DownloadSource.MATERIAL_DOCUMENT_LINK;
import static uk.gov.hmcts.cp.notification.failure.DownloadSource.SAS_DOCUMENT;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_ACCESS_REFUSED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_BAD_LINK;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_NOT_FOUND;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_TOO_LARGE;

import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.logging.OutcomeReason;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one place that says what a download's HTTP status code means: whether it fails the notification at once rather
 * than being retried, and the outcome reason the requesting team is told. Each {@link DownloadSource} keeps the codes
 * it has always failed at once; any other status is retried.
 */
@Component
public class DownloadStatusTable {
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;

    private static final Map<DownloadSource, Set<Integer>> FAILS_AT_ONCE = Map.of(
            MATERIAL_DOCUMENT_LINK,
            Set.of(HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_NOT_FOUND, HTTP_PAYLOAD_TOO_LARGE),
            SAS_DOCUMENT, Set.of(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_NOT_FOUND),
            BLOB_ATTACHMENT, Set.of(HTTP_FORBIDDEN, HTTP_NOT_FOUND));

    private static final Map<Integer, OutcomeReason> OUTCOME_REASONS = Map.ofEntries(
            entry(HTTP_UNAUTHORIZED, ATTACHMENT_ACCESS_REFUSED),
            entry(HTTP_FORBIDDEN, ATTACHMENT_ACCESS_REFUSED),
            entry(HTTP_NOT_FOUND, ATTACHMENT_NOT_FOUND),
            entry(HTTP_PAYLOAD_TOO_LARGE, ATTACHMENT_TOO_LARGE));

    public boolean failsAtOnce(final DownloadSource downloadSource, final int statusCode) {
        final Set<Integer> statusCodes = FAILS_AT_ONCE.get(downloadSource);
        return statusCodes.contains(statusCode);
    }

    /**
     * Not found (404), access refused (401 or 403) or too large (413). Anything else, including a link refused before
     * any call was made (no status code), is a link the gateway can't use.
     */
    public OutcomeReason outcomeReasonFor(final Optional<Integer> statusCode) {
        final Optional<OutcomeReason> outcomeReason = statusCode.map(OUTCOME_REASONS::get);
        return outcomeReason.orElse(ATTACHMENT_BAD_LINK);
    }
}
