package uk.gov.hmcts.cp.notification.material;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Rewrites the link Material's client library builds onto the Material base URL: a {@code localhost} /
 * {@code 127.0.0.1} link with a path under {@code /material-query-api/} and no {@code ..} segment (decoded,
 * and ignoring any {@code ;} path parameters) has its scheme, host, port and that prefix replaced by the base
 * URL, keeping the rest of the path and the query string.
 */
@Component
public class LoopbackMaterialLinkRewriter {
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP") // the loopback address Material's client library may use
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1");
    private static final String MATERIAL_QUERY_API_PREFIX = "/material-query-api/";
    private static final String PARENT_SEGMENT = "..";
    private static final char PATH_PARAMETER_SEPARATOR = ';';

    private final String baseUrl;

    public LoopbackMaterialLinkRewriter(final MaterialSettings settings) {
        final String base = settings.baseUrl().toString();
        this.baseUrl = base.endsWith("/") ? base : base + "/";
    }

    public boolean canRewrite(final URI link) {
        return LOOPBACK_HOSTS.contains(link.getHost().toLowerCase(Locale.ROOT))
                && link.getRawPath() != null
                && link.getRawPath().startsWith(MATERIAL_QUERY_API_PREFIX)
                && !hasParentSegment(link);
    }

    public URI rewrite(final URI link) {
        final StringBuilder rewritten = new StringBuilder(baseUrl)
                .append(link.getRawPath().substring(MATERIAL_QUERY_API_PREFIX.length()));
        if (link.getRawQuery() != null) {
            rewritten.append('?').append(link.getRawQuery());
        }
        return URI.create(rewritten.toString());
    }

    private static boolean hasParentSegment(final URI link) {
        return Arrays.stream(link.getPath().split("/"))
                .map(LoopbackMaterialLinkRewriter::withoutPathParameters)
                .anyMatch(PARENT_SEGMENT::equals);
    }

    private static String withoutPathParameters(final String segment) {
        final int parameters = segment.indexOf(PATH_PARAMETER_SEPARATOR);
        return parameters < 0 ? segment : segment.substring(0, parameters);
    }
}
