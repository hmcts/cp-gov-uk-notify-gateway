package uk.gov.hmcts.cp.notification.blob;

import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentDownloaderTest {
    private static final String FILE_URI = "https://examplefilestore.blob.core.windows.net/container/report.csv";
    private static final long MAX_ATTACHMENT_BYTES = 15_728_640L;

    @Mock
    private BlobClientFactory blobClientFactory;
    @Mock
    private BlobClient blobClient;

    private AttachmentDownloader attachmentDownloader;

    @BeforeEach
    void setUp() {
        attachmentDownloader = new AttachmentDownloader(blobClientFactory, MAX_ATTACHMENT_BYTES);
        when(blobClientFactory.blobClientFor(FILE_URI)).thenReturn(blobClient);
    }

    @Test
    void a_403_forbidden_is_mapped_to_a_permanent_failure() {
        final BlobStorageException forbidden = blobStorageException(403);
        when(blobClient.getProperties()).thenThrow(forbidden);

        assertThatThrownBy(() -> attachmentDownloader.download(FILE_URI))
                .isInstanceOfSatisfying(PermanentBlobException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(403));
    }

    @Test
    void a_transient_storage_error_propagates_and_is_not_a_permanent_failure() {
        final BlobStorageException transientError = blobStorageException(500);
        when(blobClient.getProperties()).thenThrow(transientError);

        assertThatThrownBy(() -> attachmentDownloader.download(FILE_URI))
                .isInstanceOf(BlobStorageException.class)
                .isNotInstanceOf(PermanentBlobException.class);
    }

    private static BlobStorageException blobStorageException(final int statusCode) {
        final HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(statusCode);
        return new BlobStorageException("blob storage error " + statusCode, response, null);
    }
}
