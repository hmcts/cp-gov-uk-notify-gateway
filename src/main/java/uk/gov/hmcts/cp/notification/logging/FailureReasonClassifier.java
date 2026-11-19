package uk.gov.hmcts.cp.notification.logging;

import static java.util.Optional.empty;
import static java.util.Set.of;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.UNREACHABLE;

import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.failure.CauseChain;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Works out the {@link FailureReason} for a call that failed without a status code, from the failure and its causes:
 * a timeout means the dependency did not respond; any other I/O failure (refused connection, unknown host, TLS
 * handshake) means it could not be reached. Anything else has no reason.
 */
@Component
public class FailureReasonClassifier {
    private static final Set<Class<? extends Throwable>> TIMEOUTS =
            of(HttpTimeoutException.class, SocketTimeoutException.class, TimeoutException.class);
    private static final Set<Class<? extends Throwable>> INPUT_OUTPUT_FAILURES = of(IOException.class);

    public Optional<FailureReason> classify(final Throwable throwable) {
        final CauseChain causeChain = new CauseChain(throwable);
        final Optional<FailureReason> failureReason;
        if (causeChain.containsAnyOf(TIMEOUTS)) {
            failureReason = Optional.of(NO_RESPONSE);
        } else if (causeChain.containsAnyOf(INPUT_OUTPUT_FAILURES)) {
            failureReason = Optional.of(UNREACHABLE);
        } else {
            failureReason = empty();
        }
        return failureReason;
    }

    /** A status code says why on its own; without one, the failure's causes may say why. */
    public Optional<FailureReason> classify(final Optional<Integer> httpStatusCode, final Throwable throwable) {
        final Optional<FailureReason> failureReason;
        if (httpStatusCode.isPresent()) {
            failureReason = empty();
        } else {
            failureReason = classify(throwable);
        }
        return failureReason;
    }
}
