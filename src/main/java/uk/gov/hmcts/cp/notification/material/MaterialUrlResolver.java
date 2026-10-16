package uk.gov.hmcts.cp.notification.material;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Turns a producer's {@code materialUrl} into the URL the gateway may call, by host:
 * <ol>
 *   <li>already on the Material service ({@link MaterialHostMatcher}) — used exactly as sent;</li>
 *   <li>the {@code localhost} link Material's client library builds ({@link LoopbackMaterialLinkRewriter}) —
 *       rewritten onto the Material base URL;</li>
 *   <li>anything else is refused, with no HTTP call, so the system user never leaves for a host that is
 *       not Material.</li>
 * </ol>
 */
@Component
public class MaterialUrlResolver {
    private static final String REFUSED = "Material link is not on the Material service";

    private final MaterialHostMatcher hostMatcher;
    private final LoopbackMaterialLinkRewriter loopbackRewriter;

    public MaterialUrlResolver(final MaterialHostMatcher hostMatcher, final LoopbackMaterialLinkRewriter loopbackRewriter) {
        this.hostMatcher = hostMatcher;
        this.loopbackRewriter = loopbackRewriter;
    }

    public URI resolve(final String materialUrl) {
        final URI link = parse(materialUrl);
        final URI resolved;
        if (hostMatcher.isOnMaterialHost(link)) {
            resolved = link;
        } else if (loopbackRewriter.canRewrite(link)) {
            resolved = loopbackRewriter.rewrite(link);
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
}
