package uk.gov.hmcts.cp.notification.failure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static java.util.Set.of;
import static org.assertj.core.api.Assertions.assertThat;

class CauseChainTest {

    @Test
    void should_contain_the_failure_itself() {
        final ConnectException connectException = new ConnectException("Connection refused");

        final CauseChain causeChain = new CauseChain(connectException);

        assertThat(causeChain.containsAnyOf(of(ConnectException.class))).isTrue();
    }

    @Test
    void should_contain_a_cause_that_matches_a_condition() {
        final SocketTimeoutException socketTimeoutException = new SocketTimeoutException("Connect timed out");
        final IOException ioException = new IOException("wrapped", socketTimeoutException);

        final CauseChain causeChain = new CauseChain(ioException);

        assertThat(causeChain.containsMatching(throwable -> "Connect timed out".equals(throwable.getMessage())))
                .isTrue();
        assertThat(causeChain.containsMatching(throwable -> "Read timed out".equals(throwable.getMessage())))
                .isFalse();
    }

    @Test
    void should_contain_a_cause_however_deep_and_its_supertypes() {
        final ConnectException connectException = new ConnectException("Connection refused");
        final UncheckedIOException uncheckedIoException = new UncheckedIOException(connectException);
        final IllegalStateException wrapper = new IllegalStateException("send failed", uncheckedIoException);

        final CauseChain causeChain = new CauseChain(wrapper);

        assertThat(causeChain.containsAnyOf(of(ConnectException.class))).isTrue();
        assertThat(causeChain.containsAnyOf(of(IOException.class))).isTrue();
    }

    @Test
    void should_not_contain_a_type_that_is_nowhere_in_the_chain() {
        final ConnectException connectException = new ConnectException("Connection refused");
        final IllegalStateException wrapper = new IllegalStateException("send failed", connectException);

        final CauseChain causeChain = new CauseChain(wrapper);

        assertThat(causeChain.containsAnyOf(of(SocketTimeoutException.class))).isFalse();
    }

    @Test
    void should_stop_when_the_causes_go_round_in_a_cycle() {
        final CyclicException first = new CyclicException("first");
        final CyclicException second = new CyclicException("second");
        first.setCyclicCause(second);
        second.setCyclicCause(first);

        final CauseChain causeChain = new CauseChain(first);

        assertThat(causeChain.containsAnyOf(of(IOException.class))).isFalse();
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
