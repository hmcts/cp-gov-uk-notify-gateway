package uk.gov.hmcts.cp.notification.material;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.sender.Attachment;

import java.net.URI;
import java.util.UUID;

/**
 * Obtains a Material document for a notification: resolve the {@code materialUrl} onto the Material
 * service, ask Material for a fresh SAS link, check the link, then download it credential-less. Each stage
 * writes one log line. The Material stage never logs a host or URL; the SAS stage logs only the
 * storage host — never the SAS query string or the document content.
 */
@Component
public class MaterialAttachmentDownloader {
    private static final Logger LOG = LoggerFactory.getLogger(MaterialAttachmentDownloader.class);

    private final MaterialUrlResolver urlResolver;
    private final MaterialClient materialClient;
    private final SasLinkValidator sasLinkValidator;
    private final SasDocumentFetcher sasDocumentFetcher;

    public MaterialAttachmentDownloader(
            final MaterialUrlResolver urlResolver,
            final MaterialClient materialClient,
            final SasLinkValidator sasLinkValidator,
            final SasDocumentFetcher sasDocumentFetcher) {
        this.urlResolver = urlResolver;
        this.materialClient = materialClient;
        this.sasLinkValidator = sasLinkValidator;
        this.sasDocumentFetcher = sasDocumentFetcher;
    }

    public Attachment download(final String materialUrl, final UUID notificationId) {
        final long materialStarted = System.nanoTime();
        final MaterialDocumentLink link =
                materialClient.requestDocumentLink(urlResolver.resolve(materialUrl), notificationId);
        LOG.info("Material attachment stage=material notificationId={} status=200 elapsedMs={}",
                notificationId, elapsedMs(materialStarted));

        final long sasStarted = System.nanoTime();
        final URI sasLink = sasLinkValidator.validate(link.sasLink());
        final byte[] document = sasDocumentFetcher.fetch(sasLink);
        LOG.info("Material attachment stage=sas notificationId={} host={} status=200 bytes={} elapsedMs={}",
                notificationId, sasLink.getHost(), document.length, elapsedMs(sasStarted));

        return new Attachment(document, link.filename());
    }

    private static long elapsedMs(final long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
