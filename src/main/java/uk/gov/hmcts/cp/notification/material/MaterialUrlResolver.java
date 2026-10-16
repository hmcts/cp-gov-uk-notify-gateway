package uk.gov.hmcts.cp.notification.material;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a producer's {@code materialUrl} into the URL the gateway may call, by host:
 * <ol>
 *   <li>scheme, host and port equal to the Material base URL's — used exactly as sent;</li>
 *   <li>{@code localhost} / {@code 127.0.0.1} with a path under {@code /material-query-api/} and no {@code ..} segment — decoded, and ignoring any {@code ;} path parameters (what
 *       Material's client library builds) — scheme, host, port and that prefix are replaced by the base
 *       URL, keeping the rest of the path and the query string;</li>
 *   <li>anything else is refused, with no HTTP call, so the system user never leaves for a host that is
 *       not Material.</li>
 * </ol>
 */
@Component
public class MaterialUrlResolver {
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP") // the loopback address Material's client library may use
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1");
    private static final Map<String, Integer> DEFAULT_PORTS = Map.of("http", 80, "https", 443);
    private static final int NO_PORT = -1;
    private static final String MATERIAL_QUERY_API_PREFIX = "/material-query-api/";
    private static final String PARENT_SEGMENT = "..";
    private static final char PATH_PARAMETER_SEPARATOR = ';';
    private static final String REFUSED = "Material link is not on the Material service";

    private final URI baseUrl;

    public MaterialUrlResolver(final MaterialSettings settings) {
        this.baseUrl = settings.baseUrl();
    }

    public URI resolve(final String materialUrl) {
        final URI link = parse(materialUrl);
        final URI resolved;
        if (isOnMaterialHost(link)) {
            resolved = link;
        } else if (isLoopback(link) && isUnderMaterialQueryApi(link)) {
            resolved = rewriteOntoBaseUrl(link);
        } else {
            throw MaterialAttachmentException.permanent(REFUSED, null);
        }
        return resolved;
    }

    private static URI parse(final String materialUrl) {
        final URI link;
        try {
            link = new URI(materialUrl.trim());
        } catch (final URISyntaxException e) {
            throw MaterialAttachmentException.permanent(REFUSED, null);
        }
        if (link.getScheme() == null || link.getHost() == null) {
            throw MaterialAttachmentException.permanent(REFUSED, null);
        }
        return link;
    }

    private boolean isOnMaterialHost(final URI link) {
        return baseUrl.getScheme().equalsIgnoreCase(link.getScheme())
                && baseUrl.getHost().equalsIgnoreCase(link.getHost())
                && effectivePort(baseUrl) == effectivePort(link);
    }

    private static boolean isUnderMaterialQueryApi(final URI link) {
        return link.getRawPath() != null
                && link.getRawPath().startsWith(MATERIAL_QUERY_API_PREFIX)
                && !hasParentSegment(link);
    }

    private static boolean hasParentSegment(final URI link) {
        return Arrays.stream(link.getPath().split("/"))
                .map(MaterialUrlResolver::withoutPathParameters)
                .anyMatch(PARENT_SEGMENT::equals);
    }

    private static String withoutPathParameters(final String segment) {
        final int parameters = segment.indexOf(PATH_PARAMETER_SEPARATOR);
        return parameters < 0 ? segment : segment.substring(0, parameters);
    }

    private static boolean isLoopback(final URI link) {
        return LOOPBACK_HOSTS.contains(link.getHost().toLowerCase(Locale.ROOT));
    }

    private URI rewriteOntoBaseUrl(final URI link) {
        final String base = baseUrl.toString();
        final StringBuilder rewritten = new StringBuilder(base.endsWith("/") ? base : base + "/")
                .append(link.getRawPath().substring(MATERIAL_QUERY_API_PREFIX.length()));
        if (link.getRawQuery() != null) {
            rewritten.append('?').append(link.getRawQuery());
        }
        return URI.create(rewritten.toString());
    }

    private static int effectivePort(final URI uri) {
        return uri.getPort() == NO_PORT
                ? DEFAULT_PORTS.getOrDefault(uri.getScheme().toLowerCase(Locale.ROOT), NO_PORT)
                : uri.getPort();
    }
}
