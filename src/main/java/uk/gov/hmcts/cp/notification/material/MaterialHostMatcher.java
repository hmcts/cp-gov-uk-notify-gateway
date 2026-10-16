package uk.gov.hmcts.cp.notification.material;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Whether a link is already on the Material service: same scheme, host and port as the Material base URL,
 * treating a missing port as the scheme's default.
 */
@Component
public class MaterialHostMatcher {
    private static final Map<String, Integer> DEFAULT_PORTS = Map.of("http", 80, "https", 443);
    private static final int NO_PORT = -1;

    private final URI baseUrl;

    public MaterialHostMatcher(final MaterialSettings settings) {
        this.baseUrl = settings.baseUrl();
    }

    public boolean isOnMaterialHost(final URI link) {
        return baseUrl.getScheme().equalsIgnoreCase(link.getScheme())
                && baseUrl.getHost().equalsIgnoreCase(link.getHost())
                && effectivePort(baseUrl) == effectivePort(link);
    }

    private static int effectivePort(final URI uri) {
        return uri.getPort() == NO_PORT
                ? DEFAULT_PORTS.getOrDefault(uri.getScheme().toLowerCase(Locale.ROOT), NO_PORT)
                : uri.getPort();
    }
}
