package uk.gov.hmcts.cp.notification.material;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

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
 * the text after {@code filename=}).
 */
@Component
public class MaterialClient {
    public static final String CJSCPPUID = "CJSCPPUID";
    public static final String CPPCLIENTCORRELATIONID = "CPPCLIENTCORRELATIONID";
    public static final String MATERIAL_MEDIA_TYPE = "application/vnd.material.query.material+json";

    private static final int HTTP_OK = 200;
    private static final String FILENAME_MARKER = "filename=";

    private final HttpClient httpClient;
    private final String systemUserId;
    private final Duration readTimeout;
    private final Duration deadline;

    public MaterialClient(
            @Qualifier(MaterialConfig.MATERIAL_HTTP_CLIENT) final HttpClient httpClient,
            final MaterialSettings settings) {
        this.httpClient = httpClient;
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
        if (response.statusCode() != HTTP_OK) {
            throw new MaterialAttachmentException(
                    "Material did not return the document (HTTP " + response.statusCode() + ")", response.statusCode());
        }
        final String sasLink = response.headers().firstValue("Location")
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new MaterialAttachmentException("Material answered without a document link", HTTP_OK));
        final String filename = filenameFrom(response.headers().firstValue("Content-Location"))
                .orElseThrow(() -> new MaterialAttachmentException("Material answered without a document filename", HTTP_OK));
        return new MaterialDocumentLink(sasLink, filename);
    }

    private HttpResponse<Void> send(final HttpRequest request) {
        final CompletableFuture<HttpResponse<Void>> pending =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        try {
            return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            pending.cancel(true);
            throw new MaterialAttachmentException("Material did not answer in time", e);
        } catch (final ExecutionException e) {
            throw new MaterialAttachmentException("Material could not be reached", e.getCause());
        } catch (final InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new MaterialAttachmentException("Material request was interrupted", e);
        }
    }

    private static Optional<String> filenameFrom(final Optional<String> contentLocation) {
        return contentLocation
                .filter(value -> value.contains(FILENAME_MARKER))
                .map(value -> value.substring(value.indexOf(FILENAME_MARKER) + FILENAME_MARKER.length()))
                .filter(name -> !name.isBlank());
    }
}
