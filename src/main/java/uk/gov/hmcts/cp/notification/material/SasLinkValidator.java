package uk.gov.hmcts.cp.notification.material;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Checks the SAS link Material returns before anything is fetched from it: it must be {@code https} on an
 * Azure Blob Storage host. The rules are built in, with no configuration switch.
 */
@Component
public class SasLinkValidator {
    private static final String HTTPS = "https";
    private static final String AZURE_BLOB_HOST_SUFFIX = ".blob.core.windows.net";
    private static final String REJECTED = "Material returned a document link that is not an https Azure Blob Storage link";

    public URI validate(final String sasLink) {
        final URI link;
        try {
            link = new URI(sasLink);
        } catch (final URISyntaxException e) {
            throw new MaterialAttachmentException(REJECTED);
        }
        if (link.getScheme() == null || !isAllowedScheme(link.getScheme().toLowerCase(Locale.ROOT))
                || link.getHost() == null || !isAllowedHost(link.getHost().toLowerCase(Locale.ROOT))) {
            throw new MaterialAttachmentException(REJECTED);
        }
        return link;
    }

    protected boolean isAllowedScheme(final String scheme) {
        return HTTPS.equals(scheme);
    }

    protected boolean isAllowedHost(final String host) {
        return host.endsWith(AZURE_BLOB_HOST_SUFFIX) && host.length() > AZURE_BLOB_HOST_SUFFIX.length();
    }
}
