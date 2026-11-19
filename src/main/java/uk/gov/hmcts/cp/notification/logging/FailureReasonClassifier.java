package uk.gov.hmcts.cp.notification.logging;

import static java.util.Locale.ROOT;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static java.util.Set.of;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.UNREACHABLE;

import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.failure.CauseChain;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Works out the {@link FailureReason} for a call that failed without a status code, from the failure and its causes:
 * a timeout while connecting, a refused connection, an unknown host or a failed TLS handshake means it could not be
 * reached; any other timeout means it did not respond. Anything else has no reason.
 */
@Component
public class FailureReasonClassifier {
    private static final Set<Class<? extends Throwable>> TIMEOUTS =
            of(HttpTimeoutException.class, SocketTimeoutException.class, TimeoutException.class);
    private static final Set<Class<? extends Throwable>> INPUT_OUTPUT_FAILURES = of(IOException.class);
    private static final String CONNECT_TIMED_OUT = "connect timed out";

    public Optional<FailureReason> classify(final Throwable throwable) {
        final CauseChain causeChain = new CauseChain(throwable);
        final Optional<FailureReason> failureReason;
        if (causeChain.containsMatching(this::isConnectTimeout)) {
            failureReason = Optional.of(UNREACHABLE);
        } else if (causeChain.containsAnyOf(TIMEOUTS)) {
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

    /**
     * Java's HTTP client says so with {@link HttpConnectTimeoutException}; {@code HttpURLConnection}, which the
     * GOV.UK Notify client uses, with a {@link SocketTimeoutException} whose message is "Connect timed out".
     */
    private boolean isConnectTimeout(final Throwable throwable) {
        final boolean connectTimeout;
        if (throwable instanceof HttpConnectTimeoutException) {
            connectTimeout = true;
        } else if (throwable instanceof SocketTimeoutException socketTimeoutException) {
            final Optional<String> message = ofNullable(socketTimeoutException.getMessage());
            final Optional<String> lowerCaseMessage = message.map(text -> text.toLowerCase(ROOT));
            connectTimeout = lowerCaseMessage.filter(text -> text.contains(CONNECT_TIMED_OUT)).isPresent();
        } else {
            connectTimeout = false;
        }
        return connectTimeout;
    }
}
