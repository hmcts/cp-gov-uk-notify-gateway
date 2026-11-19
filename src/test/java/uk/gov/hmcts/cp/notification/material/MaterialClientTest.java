package uk.gov.hmcts.cp.notification.material;

import static java.lang.Thread.currentThread;
import static java.lang.Thread.interrupted;
import static java.net.URI.create;
import static java.time.Duration.ofMillis;
import static java.util.UUID.fromString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialSettingsFactory.SYSTEM_USER_ID;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.failure.DownloadStatusTable;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

@ExtendWith(MockitoExtension.class)
class MaterialClientTest {
    private static final URI MATERIAL_URI = create("http://material-service.example.internal/material/1");
    private static final UUID NOTIFICATION_ID = fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80");

    private final CompletableFuture<Object> responseThatNeverArrives = new CompletableFuture<>();

    @Mock
    private HttpClient httpClient;

    private MaterialClient materialClient;

    @BeforeEach
    void createMaterialClientWithAOneMillisecondDeadline() {
        final MaterialSettings materialSettings = new MaterialSettings(
                SYSTEM_USER_ID, create("http://material-service.example.internal"), ofMillis(1), ofMillis(1), ofMillis(1));
        materialClient = new MaterialClient(httpClient, new DownloadStatusTable(), materialSettings);
        doReturn(responseThatNeverArrives).when(httpClient).sendAsync(any(), any());
    }

    @Test
    void should_fail_as_retryable_and_cancel_the_request_when_material_does_not_answer_in_time() {
        final MaterialAttachmentException materialAttachmentException = catchThrowableOfType(
                MaterialAttachmentException.class,
                () -> materialClient.requestDocumentLink(MATERIAL_URI, NOTIFICATION_ID));

        assertThat(materialAttachmentException.isRetryable()).isTrue();
        assertThat(materialAttachmentException.getDependency()).isEqualTo(MATERIAL);
        assertThat(materialAttachmentException.getMessage()).isEqualTo("Material did not answer in time");
        assertThat(materialAttachmentException.getCause()).isInstanceOf(TimeoutException.class);
        assertThat(responseThatNeverArrives.isCancelled()).isTrue();
    }

    @Test
    void should_fail_as_retryable_and_keep_the_thread_interrupted_when_interrupted_while_waiting() {
        currentThread().interrupt();

        final MaterialAttachmentException materialAttachmentException = catchThrowableOfType(
                MaterialAttachmentException.class,
                () -> materialClient.requestDocumentLink(MATERIAL_URI, NOTIFICATION_ID));

        assertThat(interrupted()).isTrue();
        assertThat(materialAttachmentException.isRetryable()).isTrue();
        assertThat(materialAttachmentException.getMessage()).isEqualTo("Material request was interrupted");
        assertThat(responseThatNeverArrives.isCancelled()).isTrue();
    }
}
