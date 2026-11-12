package uk.gov.hmcts.cp.notification.logging;

import static java.util.Collections.newSetFromMap;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.UNREACHABLE;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
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

    public Optional<FailureReason> classify(final Throwable throwable) {
        final List<Throwable> causeChain = causeChainOf(throwable);
        boolean timedOut = false;
        boolean inputOutputFailure = false;
        for (final Throwable throwableInChain : causeChain) {
            timedOut = timedOut || isTimeout(throwableInChain);
            inputOutputFailure = inputOutputFailure || throwableInChain instanceof IOException;
        }
        Optional<FailureReason> failureReason = Optional.empty();
        if (timedOut) {
            failureReason = Optional.of(NO_RESPONSE);
        } else if (inputOutputFailure) {
            failureReason = Optional.of(UNREACHABLE);
        }
        return failureReason;
    }

    private boolean isTimeout(final Throwable throwable) {
        return throwable instanceof HttpTimeoutException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof TimeoutException;
    }

    private List<Throwable> causeChainOf(final Throwable throwable) {
        final Set<Throwable> visitedThrowables = newSetFromMap(new IdentityHashMap<>());
        final List<Throwable> causeChain = new ArrayList<>();
        Throwable throwableInChain = throwable;
        while (throwableInChain != null && visitedThrowables.add(throwableInChain)) {
            causeChain.add(throwableInChain);
            throwableInChain = throwableInChain.getCause();
        }
        return causeChain;
    }
}
