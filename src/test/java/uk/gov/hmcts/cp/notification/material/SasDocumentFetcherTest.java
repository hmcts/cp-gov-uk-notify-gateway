package uk.gov.hmcts.cp.notification.material;

import static java.lang.Thread.currentThread;
import static java.lang.Thread.interrupted;
import static java.net.URI.create;
import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialSettingsFactory.SYSTEM_USER_ID;
import static uk.gov.hmcts.cp.notification.logging.Dependency.BLOB_STORAGE;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.failure.DownloadStatusTable;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

@ExtendWith(MockitoExtension.class)
class SasDocumentFetcherTest {
    private static final URI SAS_LINK = create("https://material.blob.core.windows.net/documents/1?sig=abc");

    private final CompletableFuture<Object> responseThatNeverArrives = new CompletableFuture<>();

    @Mock
    private HttpClient httpClient;

    private SasDocumentFetcher sasDocumentFetcher;

    @BeforeEach
    void createFetcherWithAOneMillisecondDeadline() {
        final MaterialSettings materialSettings = new MaterialSettings(
                SYSTEM_USER_ID, create("http://material-service.example.internal"), ofMillis(1), ofMillis(1), ofMillis(1));
        sasDocumentFetcher = new SasDocumentFetcher(httpClient, new DownloadStatusTable(), materialSettings, 1_024);
        doReturn(responseThatNeverArrives).when(httpClient).sendAsync(any(), any());
    }

    @Test
    void should_fail_as_retryable_and_cancel_the_download_when_it_does_not_finish_in_time() {
        final MaterialAttachmentException materialAttachmentException =
                catchThrowableOfType(MaterialAttachmentException.class, () -> sasDocumentFetcher.fetch(SAS_LINK));

        assertThat(materialAttachmentException.isRetryable()).isTrue();
        assertThat(materialAttachmentException.getDependency()).isEqualTo(BLOB_STORAGE);
        assertThat(materialAttachmentException.getMessage()).isEqualTo("The Material document was not downloaded in time");
        assertThat(materialAttachmentException.getCause()).isInstanceOf(TimeoutException.class);
        assertThat(responseThatNeverArrives.isCancelled()).isTrue();
    }

    @Test
    void should_fail_as_retryable_and_keep_the_thread_interrupted_when_interrupted_while_waiting() {
        currentThread().interrupt();

        final MaterialAttachmentException materialAttachmentException =
                catchThrowableOfType(MaterialAttachmentException.class, () -> sasDocumentFetcher.fetch(SAS_LINK));

        assertThat(interrupted()).isTrue();
        assertThat(materialAttachmentException.isRetryable()).isTrue();
        assertThat(materialAttachmentException.getMessage())
                .isEqualTo("The Material document download was interrupted");
        assertThat(responseThatNeverArrives.isCancelled()).isTrue();
    }
}
