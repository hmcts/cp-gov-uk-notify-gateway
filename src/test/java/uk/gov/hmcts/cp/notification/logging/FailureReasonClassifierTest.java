package uk.gov.hmcts.cp.notification.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLHandshakeException;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.UNREACHABLE;

class FailureReasonClassifierTest {
    private final FailureReasonClassifier failureReasonClassifier = new FailureReasonClassifier();

    @Test
    void classify_should_say_no_response_for_an_http_timeout() {
        final HttpTimeoutException httpTimeoutException = new HttpTimeoutException("request timed out");

        final Optional<FailureReason> failureReason = failureReasonClassifier.classify(httpTimeoutException);

        assertThat(failureReason).contains(NO_RESPONSE);
    }

    @Test
    void classify_should_say_no_response_for_a_socket_timeout() {
        final SocketTimeoutException socketTimeoutException = new SocketTimeoutException("Read timed out");

        final Optional<FailureReason> failureReason = failureReasonClassifier.classify(socketTimeoutException);

        assertThat(failureReason).contains(NO_RESPONSE);
    }

    @Test
    void classify_should_say_no_response_for_a_concurrent_timeout() {
        final TimeoutException timeoutException = new TimeoutException("no answer in time");

        final Optional<FailureReason> failureReason = failureReasonClassifier.classify(timeoutException);

        assertThat(failureReason).contains(NO_RESPONSE);
    }

    @Test
    void classify_should_say_unreachable_for_a_refused_connection() {
        final ConnectException connectException = new ConnectException("Connection refused");

        final Optional<FailureReason> failureReason = failureReasonClassifier.classify(connectException);

        assertThat(failureReason).contains(UNREACHABLE);
    }

    @Test
    void classify_should_say_unreachable_for_an_unknown_host() {
        final UnknownHostException unknownHostException = new UnknownHostException("api.notifications.service.gov.uk");

        final Optional<FailureReason> failureReason = failureReasonClassifier.classify(unknownHostException);

        assertThat(failureReason).contains(UNREACHABLE);
    }

    @Test
    void classify_should_say_unreachable_for_a_failed_tls_handshake() {
        final SSLHandshakeException sslHandshakeException = new SSLHandshakeException("handshake failed");

        final Optional<FailureReason> failureReason = failureReasonClassifier.classify(sslHandshakeException);

        assertThat(failureReason).contains(UNREACHABLE);
    }

    @Test
    void classify_should_find_an_input_output_failure_among_the_causes() {
        final ConnectException connectException = new ConnectException("Connection refused");
        final RuntimeException runtimeException = new RuntimeException("Gov.Notify send failed", connectException);
        final IllegalStateException illegalStateException = new IllegalStateException("wrapped", runtimeException);

        final Optional<FailureReason> failureReason = failureReasonClassifier.classify(illegalStateException);

        assertThat(failureReason).contains(UNREACHABLE);
    }

    @Test
    void classify_should_prefer_a_timeout_among_the_causes_to_another_input_output_failure() {
        final SocketTimeoutException socketTimeoutException = new SocketTimeoutException("Read timed out");
        final UncheckedIOException uncheckedIOException =
                new UncheckedIOException(new IOException("read failed", socketTimeoutException));
        final ExecutionException executionException = new ExecutionException(uncheckedIOException);

        final Optional<FailureReason> failureReason = failureReasonClassifier.classify(executionException);

        assertThat(failureReason).contains(NO_RESPONSE);
    }

    @Test
    void classify_should_say_no_response_for_a_timeout_caused_by_another_input_output_failure() {
        final ConnectException connectException = new ConnectException("Connection reset");
        final SocketTimeoutException socketTimeoutException = new SocketTimeoutException("Read timed out");
        socketTimeoutException.initCause(connectException);

        final Optional<FailureReason> failureReason = failureReasonClassifier.classify(socketTimeoutException);

        assertThat(failureReason).contains(NO_RESPONSE);
    }

    @Test
    void classify_should_give_no_reason_when_no_cause_is_an_input_output_failure() {
        final IllegalArgumentException illegalArgumentException = new IllegalArgumentException("bad template");
        final RuntimeException runtimeException = new RuntimeException("Gov.Notify send failed", illegalArgumentException);

        final Optional<FailureReason> failureReason = failureReasonClassifier.classify(runtimeException);

        assertThat(failureReason).isEmpty();
    }

    @Test
    void classify_should_stop_when_the_causes_go_round_in_a_cycle() {
        final CyclicException firstCyclicException = new CyclicException("first");
        final CyclicException secondCyclicException = new CyclicException("second");
        firstCyclicException.setCyclicCause(secondCyclicException);
        secondCyclicException.setCyclicCause(firstCyclicException);

        final Optional<FailureReason> failureReason = failureReasonClassifier.classify(firstCyclicException);

        assertThat(failureReason).isEmpty();
    }

    // Throwable.initCause refuses a cycle, so the cause is overridden to build one.
    private static final class CyclicException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private transient Throwable cyclicCause;

        private CyclicException(final String message) {
            super(message);
        }

        private void setCyclicCause(final Throwable cyclicCause) {
            this.cyclicCause = cyclicCause;
        }

        @Override
        public synchronized Throwable getCause() {
            return cyclicCause;
        }
    }
}
