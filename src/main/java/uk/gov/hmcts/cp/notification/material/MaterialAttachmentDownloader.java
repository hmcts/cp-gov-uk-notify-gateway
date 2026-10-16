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
 * writes one log line, on success or failure, with the stage, outcome, status and elapsed time; a failure
 * line carries the exception's message only, never the throwable. The Material stage never logs a host or
 * URL; the SAS stage logs only the storage host (once the link has passed its checks) — never the SAS query
 * string or the document content.
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
        final MaterialDocumentLink link = requestDocumentLink(materialUrl, notificationId);
        final byte[] document = fetchDocument(link.sasLink(), notificationId);
        return new Attachment(document, link.filename());
    }

    private MaterialDocumentLink requestDocumentLink(final String materialUrl, final UUID notificationId) {
        final long started = System.nanoTime();
        try {
            final MaterialDocumentLink link =
                    materialClient.requestDocumentLink(urlResolver.resolve(materialUrl), notificationId);
            LOG.info("Material attachment stage=material notificationId={} outcome=ok status=200 elapsedMs={}",
                    notificationId, elapsedMs(started));
            return link;
        } catch (final MaterialAttachmentException e) {
            LOG.warn("Material attachment stage=material notificationId={} outcome={} status={} elapsedMs={} reason={}",
                    notificationId, outcome(e), e.getStatusCode(), elapsedMs(started), e.getMessage());
            throw e;
        }
    }

    private byte[] fetchDocument(final String sasLink, final UUID notificationId) {
        final long started = System.nanoTime();
        String host = null;
        try {
            final URI validated = sasLinkValidator.validate(sasLink);
            host = validated.getHost();
            final byte[] document = sasDocumentFetcher.fetch(validated);
            LOG.info("Material attachment stage=sas notificationId={} host={} outcome=ok status=200 bytes={} elapsedMs={}",
                    notificationId, host, document.length, elapsedMs(started));
            return document;
        } catch (final MaterialAttachmentException e) {
            LOG.warn("Material attachment stage=sas notificationId={} host={} outcome={} status={} elapsedMs={} reason={}",
                    notificationId, host, outcome(e), e.getStatusCode(), elapsedMs(started), e.getMessage());
            throw e;
        }
    }

    private static String outcome(final MaterialAttachmentException failure) {
        return failure.isRetryable() ? "transient" : "permanent";
    }

    private static long elapsedMs(final long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
