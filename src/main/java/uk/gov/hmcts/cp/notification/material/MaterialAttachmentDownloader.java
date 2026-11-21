package uk.gov.hmcts.cp.notification.material;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.hmcts.cp.notification.logging.Dependency.BLOB_STORAGE;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;
import static uk.gov.hmcts.cp.notification.logging.LogField.CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.LogField.DEPENDENCY;
import static uk.gov.hmcts.cp.notification.logging.LogField.OPERATION;
import static uk.gov.hmcts.cp.notification.logging.LogField.OUTCOME;
import static uk.gov.hmcts.cp.notification.logging.LogField.STATUS_CODE;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.logging.CallOutcome;
import uk.gov.hmcts.cp.notification.logging.Channel;
import uk.gov.hmcts.cp.notification.logging.Dependency;
import uk.gov.hmcts.cp.notification.logging.Operation;
import uk.gov.hmcts.cp.notification.sender.Attachment;

import java.net.URI;
import java.util.UUID;

/**
 * Obtains a Material document for a notification: resolve the {@code materialUrl} onto the Material
 * service, ask Material for a fresh SAS link, check the link, then download it from blob storage credential-less.
 * Each call that succeeds writes one line with the call's JSON fields (PEG-3720). A call that fails writes one line
 * with the failure's message and how long it took, but not the call's fields: the task logs the failure with them,
 * once, so an alert never counts it twice. A line never carries the throwable, a link or the SAS query string; the
 * download's line names only the storage host, once the link has passed its checks.
 */
@Component
public class MaterialAttachmentDownloader {
    private static final int HTTP_OK = 200;
    private static final long NANOSECONDS_PER_MILLISECOND = 1_000_000;

    private final Logger logger = getLogger(MaterialAttachmentDownloader.class);

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

    public Attachment download(final String materialUrl, final UUID notificationId, final Channel channel) {
        final MaterialDocumentLink materialDocumentLink = requestDocumentLink(materialUrl, notificationId, channel);
        final byte[] document = fetchDocument(materialDocumentLink.sasLink(), notificationId, channel);
        return new Attachment(document, materialDocumentLink.filename());
    }

    private MaterialDocumentLink requestDocumentLink(
            final String materialUrl, final UUID notificationId, final Channel channel) {
        final long startedNanoseconds = System.nanoTime();
        try {
            final URI materialUri = urlResolver.resolve(materialUrl);
            final MaterialDocumentLink materialDocumentLink =
                    materialClient.requestDocumentLink(materialUri, notificationId);
            final String message = format(
                    "Obtained the Material document link for notification '%s' in '%s' ms",
                    notificationId, elapsedMilliseconds(startedNanoseconds));
            logCallSucceeded(MATERIAL, channel, message);
            return materialDocumentLink;
        } catch (final MaterialAttachmentException materialAttachmentException) {
            final String message = format(
                    "Could not obtain the Material document link for notification '%s' after '%s' ms: '%s'",
                    notificationId, elapsedMilliseconds(startedNanoseconds), materialAttachmentException.getMessage());
            logger.warn(message);
            throw materialAttachmentException;
        }
    }

    private byte[] fetchDocument(final String sasLink, final UUID notificationId, final Channel channel) {
        final long startedNanoseconds = System.nanoTime();
        try {
            final URI validatedSasLink = sasLinkValidator.validate(sasLink);
            final String storageHost = validatedSasLink.getHost();
            final byte[] document = sasDocumentFetcher.fetch(validatedSasLink);
            final String message = format(
                    "Downloaded the Material document for notification '%s' from '%s' ('%s' bytes) in '%s' ms",
                    notificationId, storageHost, document.length, elapsedMilliseconds(startedNanoseconds));
            logCallSucceeded(BLOB_STORAGE, channel, message);
            return document;
        } catch (final MaterialAttachmentException materialAttachmentException) {
            final String message = format(
                    "Could not download the Material document for notification '%s' after '%s' ms: '%s'",
                    notificationId, elapsedMilliseconds(startedNanoseconds), materialAttachmentException.getMessage());
            logger.warn(message);
            throw materialAttachmentException;
        }
    }

    private void logCallSucceeded(final Dependency dependency, final Channel channel, final String message) {
        logger.atInfo()
                .addKeyValue(DEPENDENCY, dependency.getFieldValue())
                .addKeyValue(OPERATION, Operation.DOWNLOAD.getFieldValue())
                .addKeyValue(CHANNEL, channel.getFieldValue())
                .addKeyValue(OUTCOME, CallOutcome.SUCCEEDED.getFieldValue())
                .addKeyValue(STATUS_CODE, HTTP_OK)
                .log(message);
    }

    private long elapsedMilliseconds(final long startedNanoseconds) {
        final long elapsedNanoseconds = System.nanoTime() - startedNanoseconds;
        return elapsedNanoseconds / NANOSECONDS_PER_MILLISECOND;
    }
}
