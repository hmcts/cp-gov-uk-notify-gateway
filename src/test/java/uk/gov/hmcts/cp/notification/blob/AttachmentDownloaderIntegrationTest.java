package uk.gov.hmcts.cp.notification.blob;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.notification.failure.DownloadStatusTable;
import uk.gov.hmcts.cp.notification.integration.Fixtures;
import uk.gov.hmcts.cp.notification.integration.stubs.support.AzuriteContainerSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.cp.notification.integration.stubs.AzureBlobFileStoreStub.anAzureBlobFileStore;

class AttachmentDownloaderIntegrationTest {

    private static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

    private final AttachmentDownloader attachmentDownloader = downloaderWithMaxBytes(MAX_ATTACHMENT_BYTES);

    @Test
    void downloads_attachment_bytes_for_a_valid_file_uri() {
        final byte[] content = Fixtures.loadBytes("fixtures/attachments/report.csv");
        final String blobName = "report-" + UUID.randomUUID() + ".csv";
        final String fileUri = anAzureBlobFileStore().containing(blobName, content).uriOf(blobName);

        assertThat(attachmentDownloader.download(fileUri)).isEqualTo(content);
    }

    @Test
    void missing_blob_404_raises_a_permanent_failure_not_a_retryable_error() {
        final String missing = anAzureBlobFileStore().uriOf("does-not-exist-" + UUID.randomUUID() + ".csv");

        assertThatThrownBy(() -> attachmentDownloader.download(missing))
                .isInstanceOf(PermanentBlobException.class);
    }

    @Test
    void a_blob_larger_than_the_maximum_raises_a_permanent_failure_before_buffering() {
        final AttachmentDownloader boundedDownloader = downloaderWithMaxBytes(8);
        final String blobName = "oversized-" + UUID.randomUUID() + ".csv";
        final String fileUri = anAzureBlobFileStore().containing(blobName, "well over eight bytes".getBytes())
                .uriOf(blobName);

        assertThatThrownBy(() -> boundedDownloader.download(fileUri))
                .isInstanceOf(PermanentBlobException.class);
    }

    private static AttachmentDownloader downloaderWithMaxBytes(final long maxBytes) {
        return new AttachmentDownloader(
                new BlobClientFactory(AzuriteContainerSupport.getConnectionString(), new BlobHostValidator(List.of())),
                new DownloadStatusTable(),
                maxBytes);
    }
}
