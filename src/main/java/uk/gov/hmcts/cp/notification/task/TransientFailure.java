package uk.gov.hmcts.cp.notification.task;

import uk.gov.hmcts.cp.notification.logging.Channel;

import java.util.Optional;
import java.util.UUID;

/**
 * A call that failed in a way that may recover, and the reason to record if it never does.
 *
 * @param httpStatusCode the HTTP status code the service answered with; empty when it gave no HTTP response
 */
public record TransientFailure(
        UUID notificationId,
        RetryCause retryCause,
        Channel channel,
        Optional<Integer> httpStatusCode,
        Throwable failure,
        String exhaustedReason) {
}
