package uk.gov.hmcts.cp.notification.blob;

import static java.lang.String.format;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static uk.gov.hmcts.cp.notification.failure.DownloadSource.BLOB_ATTACHMENT;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.failure.DownloadStatusTable;

import java.util.function.Supplier;

/**
 * Downloads an email attachment from blob storage ({@code fileUri}). A blob that is too large, and the statuses in
 * {@link DownloadStatusTable}, throw {@link PermanentBlobException}. Any other failure of the blob client throws
 * {@link TransientBlobException}, so the task retries it on its own schedule and marks the notification FAILED when
 * the retries run out; an exception that escaped the task would instead be run again by the task manager for ever.
 */
@Component
public class AttachmentDownloader {
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;

    private final BlobClientFactory blobClientFactory;
    private final DownloadStatusTable downloadStatusTable;
    private final long maxAttachmentBytes;

    public AttachmentDownloader(
            final BlobClientFactory blobClientFactory,
            final DownloadStatusTable downloadStatusTable,
            @Value("${cp.notification.blob.max-attachment-bytes:15728640}") final long maxAttachmentBytes) {
        this.blobClientFactory = blobClientFactory;
        this.downloadStatusTable = downloadStatusTable;
        this.maxAttachmentBytes = maxAttachmentBytes;
    }

    public byte[] download(final String fileUri) {
        final BlobClient blobClient = blobClientFactory.blobClientFor(fileUri);
        final BlobProperties blobProperties = callBlobStorage(fileUri, blobClient::getProperties);
        final long size = blobProperties.getBlobSize();
        if (size > maxAttachmentBytes) {
            throw new PermanentBlobException(
                    "Attachment " + fileUri + " is " + size + " bytes, exceeding the maximum "
                            + maxAttachmentBytes + " bytes", HTTP_PAYLOAD_TOO_LARGE, null);
        }
        final BinaryData content = callBlobStorage(fileUri, blobClient::downloadContent);
        return content.toBytes();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // the blob client reports a network failure as any runtime exception
    private <T> T callBlobStorage(final String fileUri, final Supplier<T> blobStorageCall) {
        try {
            return blobStorageCall.get();
        } catch (final BlobStorageException blobStorageException) {
            final int status = blobStorageException.getStatusCode();
            if (downloadStatusTable.failsAtOnce(BLOB_ATTACHMENT, status)) {
                throw new PermanentBlobException(
                        "Permanent blob failure (HTTP " + status + ") for " + fileUri, status, blobStorageException);
            }
            throw new TransientBlobException(
                    format("Blob storage answered HTTP '%s'", status), of(status), blobStorageException);
        } catch (final RuntimeException runtimeException) {
            throw new TransientBlobException("Blob storage could not be called", empty(), runtimeException);
        }
    }
}
