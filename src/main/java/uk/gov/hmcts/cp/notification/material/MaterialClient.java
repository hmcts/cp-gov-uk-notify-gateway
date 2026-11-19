package uk.gov.hmcts.cp.notification.material;

import static java.lang.String.format;
import static java.lang.Thread.currentThread;
import static java.util.Optional.of;
import static uk.gov.hmcts.cp.notification.failure.DownloadSource.MATERIAL_DOCUMENT_LINK;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;
import static uk.gov.hmcts.cp.notification.material.MaterialAttachmentException.permanent;
import static uk.gov.hmcts.cp.notification.material.MaterialAttachmentException.retryable;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.failure.DownloadStatusTable;
import uk.gov.hmcts.cp.notification.logging.OutcomeReason;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Queries Material ({@code query:material.query.material}) for a document as the notification-notify
 * system user and reads back the SAS link ({@code Location}) and the filename ({@code Content-Location},
 * the text after {@code filename=}). The statuses that fail at once are in {@link DownloadStatusTable}; any other
 * status, a missing link or filename, a timeout or a connection failure may recover, so is retried.
 */
@Component
public class MaterialClient {
    public static final String CJSCPPUID = "CJSCPPUID";
    public static final String CPPCLIENTCORRELATIONID = "CPPCLIENTCORRELATIONID";
    public static final String MATERIAL_MEDIA_TYPE = "application/vnd.material.query.material+json";

    private static final int HTTP_OK = 200;
    private static final String FILENAME_MARKER = "filename=";

    private final HttpClient httpClient;
    private final DownloadStatusTable downloadStatusTable;
    private final String systemUserId;
    private final Duration readTimeout;
    private final Duration deadline;

    public MaterialClient(
            @Qualifier(MaterialConfig.MATERIAL_HTTP_CLIENT) final HttpClient httpClient,
            final DownloadStatusTable downloadStatusTable,
            final MaterialSettings settings) {
        this.httpClient = httpClient;
        this.downloadStatusTable = downloadStatusTable;
        this.systemUserId = settings.systemUserId().toString();
        this.readTimeout = settings.readTimeout();
        this.deadline = settings.connectTimeout().plus(settings.readTimeout());
    }

    public MaterialDocumentLink requestDocumentLink(final URI materialUri, final UUID notificationId) {
        final HttpRequest request = HttpRequest.newBuilder(materialUri)
                .GET()
                .timeout(readTimeout)
                .header(CJSCPPUID, systemUserId)
                .header("Accept", MATERIAL_MEDIA_TYPE)
                .header(CPPCLIENTCORRELATIONID, notificationId.toString())
                .build();
        final HttpResponse<Void> response = send(request);
        final int status = response.statusCode();
        failUnlessOk(status);
        final String sasLink = response.headers().firstValue("Location")
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> retryable(MATERIAL, "Material answered without a document link", HTTP_OK));
        final String filename = filenameFrom(response.headers().firstValue("Content-Location"))
                .orElseThrow(() -> retryable(MATERIAL, "Material answered without a document filename", HTTP_OK));
        return new MaterialDocumentLink(sasLink, filename);
    }

    private void failUnlessOk(final int status) {
        if (downloadStatusTable.failsAtOnce(MATERIAL_DOCUMENT_LINK, status)) {
            throw permanent(MATERIAL, permanentFailureMessage(status), status);
        } else if (status != HTTP_OK) {
            throw retryable(MATERIAL, format("Material did not return the document (HTTP %s)", status), status);
        }
    }

    private String permanentFailureMessage(final int status) {
        final OutcomeReason outcomeReason = downloadStatusTable.outcomeReasonFor(of(status));
        return switch (outcomeReason) {
            case ATTACHMENT_ACCESS_REFUSED -> format("Material refused access to the document (HTTP %s)", status);
            case ATTACHMENT_NOT_FOUND -> format("Material has no such document (HTTP %s)", status);
            default -> format("Material rejected the document request (HTTP %s)", status);
        };
    }

    private HttpResponse<Void> send(final HttpRequest request) {
        final CompletableFuture<HttpResponse<Void>> pending =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        try {
            return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            pending.cancel(true);
            throw retryable(MATERIAL, "Material did not answer in time", null, e);
        } catch (final ExecutionException e) {
            throw retryable(MATERIAL, "Material could not be reached", null, e.getCause());
        } catch (final InterruptedException e) {
            pending.cancel(true);
            currentThread().interrupt();
            throw retryable(MATERIAL, "Material request was interrupted", null, e);
        }
    }

    private static Optional<String> filenameFrom(final Optional<String> contentLocation) {
        return contentLocation
                .filter(value -> value.contains(FILENAME_MARKER))
                .map(value -> value.substring(value.indexOf(FILENAME_MARKER) + FILENAME_MARKER.length()))
                .filter(name -> !name.isBlank());
    }
}
