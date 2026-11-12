package uk.gov.hmcts.cp.notification.sender;

import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;

import javax.net.ssl.SSLHandshakeException;

import static org.assertj.core.api.Assertions.assertThat;

class GovNotifyFailureClassifierTest {
    private final FailureReasonClassifier failureReasonClassifier = new FailureReasonClassifier();
    private final GovNotifyFailureClassifier govNotifyFailureClassifier =
            new GovNotifyFailureClassifier(failureReasonClassifier);

    @Test
    void treats_bad_request_not_found_and_too_large_as_permanent() {
        final GovNotifyException badRequest = new GovNotifyException(400, "bad request", null);
        final GovNotifyException notFound = new GovNotifyException(404, "no result found", null);
        final GovNotifyException tooLarge = new GovNotifyException(413, "too large", null);

        assertThat(govNotifyFailureClassifier.isTemporary(badRequest)).isFalse();
        assertThat(govNotifyFailureClassifier.isTemporary(notFound)).isFalse();
        assertThat(govNotifyFailureClassifier.isTemporary(tooLarge)).isFalse();
    }

    @Test
    void treats_server_rate_limit_and_auth_errors_as_temporary() {
        final GovNotifyException serverError = new GovNotifyException(500, "server error", null);
        final GovNotifyException tooManyRequests = new GovNotifyException(429, "too many requests", null);
        final GovNotifyException unauthorised = new GovNotifyException(401, "unauthorised", null);
        final GovNotifyException forbidden = new GovNotifyException(403, "forbidden", null);

        assertThat(govNotifyFailureClassifier.isTemporary(serverError)).isTrue();
        assertThat(govNotifyFailureClassifier.isTemporary(tooManyRequests)).isTrue();
        assertThat(govNotifyFailureClassifier.isTemporary(unauthorised)).isTrue();
        assertThat(govNotifyFailureClassifier.isTemporary(forbidden)).isTrue();
    }

    @Test
    void treats_no_http_response_as_temporary_when_gov_notify_could_not_be_reached() {
        final ConnectException connectException = new ConnectException("Connection refused");
        final GovNotifyException govNotifyException =
                new GovNotifyException(0, "java.net.ConnectException: Connection refused", connectException);

        assertThat(govNotifyFailureClassifier.isTemporary(govNotifyException)).isTrue();
    }

    @Test
    void treats_no_http_response_as_temporary_when_gov_notify_did_not_respond() {
        final SocketTimeoutException socketTimeoutException = new SocketTimeoutException("Read timed out");
        final GovNotifyException govNotifyException =
                new GovNotifyException(0, "java.net.SocketTimeoutException: Read timed out", socketTimeoutException);

        assertThat(govNotifyFailureClassifier.isTemporary(govNotifyException)).isTrue();
    }

    @Test
    void treats_no_http_response_as_temporary_for_a_failed_ssl_handshake() {
        final SSLHandshakeException sslHandshakeException = new SSLHandshakeException("handshake failed");
        final GovNotifyException govNotifyException = new GovNotifyException(
                0, "javax.net.ssl.SSLHandshakeException: handshake failed", sslHandshakeException);

        assertThat(govNotifyFailureClassifier.isTemporary(govNotifyException)).isTrue();
    }

    @Test
    void treats_no_http_response_without_an_input_output_failure_as_permanent() {
        final URISyntaxException uriSyntaxException = new URISyntaxException("not a uri", "bad base url");
        final GovNotifyException withoutInputOutputFailure =
                new GovNotifyException(0, "java.net.URISyntaxException: bad base url", uriSyntaxException);
        final GovNotifyException withoutCause = new GovNotifyException(0, "connection reset", null);

        assertThat(govNotifyFailureClassifier.isTemporary(withoutInputOutputFailure)).isFalse();
        assertThat(govNotifyFailureClassifier.isTemporary(withoutCause)).isFalse();
    }
}
