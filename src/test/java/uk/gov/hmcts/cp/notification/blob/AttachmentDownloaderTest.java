package uk.gov.hmcts.cp.notification.blob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.failure.DownloadStatusTable;

import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

@ExtendWith(MockitoExtension.class)
class AttachmentDownloaderTest {
    private static final String FILE_URI = "https://examplefilestore.blob.core.windows.net/container/report.csv";
    private static final long MAX_ATTACHMENT_BYTES = 15_728_640L;
    private static final byte[] CONTENT = "report".getBytes(StandardCharsets.UTF_8);

    @Mock
    private BlobClientFactory blobClientFactory;
    @Mock
    private BlobClient blobClient;
    @Mock
    private BlobProperties blobProperties;

    private AttachmentDownloader attachmentDownloader;

    @BeforeEach
    void createDownloaderForAMockedBlob() {
        attachmentDownloader = new AttachmentDownloader(blobClientFactory, new DownloadStatusTable(), MAX_ATTACHMENT_BYTES);
        when(blobClientFactory.blobClientFor(FILE_URI)).thenReturn(blobClient);
    }

    @Test
    void should_return_the_blob_content() {
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getBlobSize()).thenReturn((long) CONTENT.length);
        when(blobClient.downloadContent()).thenReturn(BinaryData.fromBytes(CONTENT));

        final byte[] content = attachmentDownloader.download(FILE_URI);

        assertThat(content).isEqualTo(CONTENT);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404})
    void should_fail_permanently_when_blob_storage_refuses_or_does_not_have_the_blob(final int statusCode) {
        final BlobStorageException blobStorageException = blobStorageException(statusCode);
        when(blobClient.getProperties()).thenThrow(blobStorageException);

        final PermanentBlobException permanentBlobException =
                catchThrowableOfType(PermanentBlobException.class, () -> attachmentDownloader.download(FILE_URI));

        assertThat(permanentBlobException.getStatusCode()).isEqualTo(statusCode);
        assertThat(permanentBlobException.getMessage())
                .isEqualTo("Permanent blob failure (HTTP " + statusCode + ") for " + FILE_URI);
        assertThat(permanentBlobException.getCause()).isSameAs(blobStorageException);
    }

    @Test
    void should_fail_permanently_without_downloading_a_blob_larger_than_the_maximum() {
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getBlobSize()).thenReturn(MAX_ATTACHMENT_BYTES + 1);

        final PermanentBlobException permanentBlobException =
                catchThrowableOfType(PermanentBlobException.class, () -> attachmentDownloader.download(FILE_URI));

        assertThat(permanentBlobException.getStatusCode()).isEqualTo(413);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500, 503})
    void should_fail_transiently_with_the_status_when_blob_storage_answers_with_any_other_status(final int statusCode) {
        final BlobStorageException blobStorageException = blobStorageException(statusCode);
        when(blobClient.getProperties()).thenThrow(blobStorageException);

        final TransientBlobException transientBlobException =
                catchThrowableOfType(TransientBlobException.class, () -> attachmentDownloader.download(FILE_URI));

        assertThat(transientBlobException.getStatusCode()).hasValue(statusCode);
        assertThat(transientBlobException.getMessage()).isEqualTo("Blob storage answered HTTP '" + statusCode + "'");
        assertThat(transientBlobException.getCause()).isSameAs(blobStorageException);
    }

    @Test
    void should_fail_transiently_without_a_status_when_the_download_fails_without_an_answer() {
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getBlobSize()).thenReturn((long) CONTENT.length);
        final UncheckedIOException uncheckedIoException =
                new UncheckedIOException(new ConnectException("Connection refused"));
        when(blobClient.downloadContent()).thenThrow(uncheckedIoException);

        final TransientBlobException transientBlobException =
                catchThrowableOfType(TransientBlobException.class, () -> attachmentDownloader.download(FILE_URI));

        assertThat(transientBlobException.getStatusCode()).isEmpty();
        assertThat(transientBlobException.getMessage()).isEqualTo("Blob storage could not be called");
        assertThat(transientBlobException.getCause()).isSameAs(uncheckedIoException);
    }

    private static BlobStorageException blobStorageException(final int statusCode) {
        final HttpResponse httpResponse = mock(HttpResponse.class);
        when(httpResponse.getStatusCode()).thenReturn(statusCode);
        return new BlobStorageException("blob storage error " + statusCode, httpResponse, null);
    }
}
