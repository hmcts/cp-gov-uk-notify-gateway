package uk.gov.hmcts.cp.notification.material;

import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Validated Material settings. Construction fails fast — and the application refuses to start — when the
 * system user is not a UUID or the base URL is not an absolute http(s) URL. Neither value is ever written
 * into an exception message or {@link #toString()}.
 */
public record MaterialSettings(
        UUID systemUserId,
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration sasConnectTimeout,
        Duration sasReadTimeout) {

    private static final String SYSTEM_USER_ID = "cp.notification.material.system-user-id (env CP_NG_SYSTEM_USER_ID)";
    private static final String BASE_URL = "cp.notification.material.base-url (env CP_NG_MATERIAL_BASE_URL)";
    private static final Set<String> HTTP_SCHEMES = Set.of("http", "https");

    public static MaterialSettings from(final MaterialProperties properties) {
        return new MaterialSettings(
                systemUserId(properties.systemUserId()),
                baseUrl(properties.baseUrl()),
                properties.connectTimeout(),
                properties.readTimeout(),
                properties.sasConnectTimeout(),
                properties.sasReadTimeout());
    }

    private static UUID systemUserId(final String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(SYSTEM_USER_ID + " must be set — Material cannot be called without it");
        }
        final String trimmed = value.trim();
        try {
            final UUID uuid = UUID.fromString(trimmed);
            if (!uuid.toString().equalsIgnoreCase(trimmed)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return uuid;
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException(SYSTEM_USER_ID + " must be a UUID");
        }
    }

    private static URI baseUrl(final String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(BASE_URL + " must be set — Material cannot be called without it");
        }
        final URI uri;
        try {
            uri = new URI(value.trim());
        } catch (final URISyntaxException e) {
            throw new IllegalStateException(BASE_URL + " must be an absolute http(s) URL");
        }
        if (uri.getScheme() == null || !HTTP_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
                || !StringUtils.hasText(uri.getHost())) {
            throw new IllegalStateException(BASE_URL + " must be an absolute http(s) URL");
        }
        return uri;
    }

    @Override
    public String toString() {
        return "MaterialSettings[systemUserId=<redacted>, baseUrl=<redacted>, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", sasConnectTimeout=" + sasConnectTimeout
                + ", sasReadTimeout=" + sasReadTimeout + "]";
    }
}
