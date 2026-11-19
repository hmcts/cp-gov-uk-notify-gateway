package uk.gov.hmcts.cp.notification.sender;

import static java.util.Optional.ofNullable;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a {@link GovNotifyException} is worth retrying. {@code 400}, {@code 404} and {@code 413} are
 * permanent, and every other HTTP status is temporary. A failure with no HTTP response is permanent unless the TLS
 * handshake failed: any other failure without a response may come after Gov.Notify accepted the notification, and
 * Gov.Notify does not de-duplicate, so a retry could send it twice.
 */
@Component
public class GovNotifyFailureClassifier {
    private static final Set<Integer> PERMANENT_HTTP_STATUSES = Set.of(400, 404, 413);
    private static final String SSL_HANDSHAKE = "SSLHandshakeException";

    public boolean isTemporary(final GovNotifyException govNotifyException) {
        final Optional<Integer> httpStatusCode = govNotifyException.getHttpStatusCode();
        final boolean temporary;
        if (httpStatusCode.isPresent()) {
            final Integer httpStatusCodeValue = httpStatusCode.get();
            temporary = !PERMANENT_HTTP_STATUSES.contains(httpStatusCodeValue);
        } else {
            temporary = sslHandshakeFailed(govNotifyException);
        }
        return temporary;
    }

    private boolean sslHandshakeFailed(final GovNotifyException govNotifyException) {
        final Optional<String> message = ofNullable(govNotifyException.getMessage());
        return message
                .filter(messageText -> messageText.contains(SSL_HANDSHAKE))
                .isPresent();
    }
}
