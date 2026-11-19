package uk.gov.hmcts.cp.notification.sender;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

import static org.assertj.core.api.Assertions.assertThat;

class GovNotifyFailureClassifierTest {
    private final GovNotifyFailureClassifier govNotifyFailureClassifier = new GovNotifyFailureClassifier();

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
    void treats_no_http_response_as_temporary_for_a_failed_ssl_handshake() {
        final SSLHandshakeException sslHandshakeException = new SSLHandshakeException("handshake failed");
        final GovNotifyException govNotifyException = new GovNotifyException(
                0, "javax.net.ssl.SSLHandshakeException: handshake failed", sslHandshakeException);

        assertThat(govNotifyFailureClassifier.isTemporary(govNotifyException)).isTrue();
    }

    @Test
    void treats_no_http_response_as_permanent_for_any_connection_failure_because_gov_notify_may_already_have_it() {
        final ConnectException connectException = new ConnectException("Connection refused");
        final GovNotifyException connectionRefused =
                new GovNotifyException(0, "java.net.ConnectException: Connection refused", connectException);
        final UnknownHostException unknownHostException = new UnknownHostException("api.notifications.service.gov.uk");
        final GovNotifyException unknownHost = new GovNotifyException(
                0, "java.net.UnknownHostException: api.notifications.service.gov.uk", unknownHostException);
        final NoRouteToHostException noRouteToHostException = new NoRouteToHostException("No route to host");
        final GovNotifyException noRouteToHost = new GovNotifyException(
                0, "java.net.NoRouteToHostException: No route to host", noRouteToHostException);
        final SocketException connectionReset = new SocketException("Connection reset");
        final GovNotifyException resetWhileReadingTheReply =
                new GovNotifyException(0, "java.net.SocketException: Connection reset", connectionReset);
        final SocketTimeoutException readTimedOut = new SocketTimeoutException("Read timed out");
        final GovNotifyException noReply =
                new GovNotifyException(0, "java.net.SocketTimeoutException: Read timed out", readTimedOut);
        final IOException unexpectedEndOfStream = new IOException("Premature EOF");
        final GovNotifyException replyCutShort =
                new GovNotifyException(0, "java.io.IOException: Premature EOF", unexpectedEndOfStream);

        assertThat(govNotifyFailureClassifier.isTemporary(connectionRefused)).isFalse();
        assertThat(govNotifyFailureClassifier.isTemporary(unknownHost)).isFalse();
        assertThat(govNotifyFailureClassifier.isTemporary(noRouteToHost)).isFalse();
        assertThat(govNotifyFailureClassifier.isTemporary(resetWhileReadingTheReply)).isFalse();
        assertThat(govNotifyFailureClassifier.isTemporary(noReply)).isFalse();
        assertThat(govNotifyFailureClassifier.isTemporary(replyCutShort)).isFalse();
    }

    @Test
    void treats_no_http_response_as_permanent_when_the_request_could_not_be_built() {
        final URISyntaxException uriSyntaxException = new URISyntaxException("not a uri", "bad base url");
        final GovNotifyException badUri =
                new GovNotifyException(0, "java.net.URISyntaxException: bad base url", uriSyntaxException);
        final MalformedURLException malformedUrlException = new MalformedURLException("no protocol: notify");
        final GovNotifyException malformedUrl =
                new GovNotifyException(0, "java.net.MalformedURLException: no protocol: notify", malformedUrlException);
        final GovNotifyException withoutCauseOrMessage = new GovNotifyException(0, null, null);

        assertThat(govNotifyFailureClassifier.isTemporary(badUri)).isFalse();
        assertThat(govNotifyFailureClassifier.isTemporary(malformedUrl)).isFalse();
        assertThat(govNotifyFailureClassifier.isTemporary(withoutCauseOrMessage)).isFalse();
    }
}
