package uk.gov.hmcts.cp.notification.sender;

import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Validated Office 365 route settings. Construction fails fast — and the application refuses to start — when
 * the endpoint is not an absolute http(s) URL (or is still XML-escaped) or the subscription key is blank. Neither value is ever written
 * into an exception message or {@link #toString()}.
 */
public record Office365Settings(URI endpoint, String subscriptionKey, Duration connectTimeout, Duration requestTimeout) {

    private static final String ENDPOINT = "cp.notification.office365.endpoint (env CP_NG_OFFICE365_ENDPOINT)";
    private static final String SUBSCRIPTION_KEY =
            "cp.notification.office365.subscription-key (env CP_NG_OFFICE365_SUBSCRIPTION_KEY)";
    private static final Set<String> HTTP_SCHEMES = Set.of("http", "https");

    public static Office365Settings from(final Office365Properties properties) {
        return new Office365Settings(endpoint(properties.endpoint()), subscriptionKey(properties.subscriptionKey()),
                properties.connectTimeout(), properties.requestTimeout());
    }

    private static URI endpoint(final String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(ENDPOINT + " must be set — the Office 365 route cannot be called without it");
        }
        if (value.contains("&amp;")) {
            throw new IllegalStateException(ENDPOINT + " must be the raw URL — it contains '&amp;', the XML-escaped "
                    + "form copied from standalone.xml; use '&'");
        }
        final URI uri;
        try {
            uri = new URI(value.trim());
        } catch (final URISyntaxException e) {
            throw new IllegalStateException(ENDPOINT + " must be an absolute http(s) URL");
        }
        if (uri.getScheme() == null || !HTTP_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
                || !StringUtils.hasText(uri.getHost())) {
            throw new IllegalStateException(ENDPOINT + " must be an absolute http(s) URL");
        }
        return uri;
    }

    private static String subscriptionKey(final String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    SUBSCRIPTION_KEY + " must be set — the Office 365 route cannot be called without it");
        }
        return value.trim();
    }

    @Override
    public String toString() {
        return "Office365Settings[endpoint=<redacted>, subscriptionKey=<redacted>, connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout + "]";
    }
}
