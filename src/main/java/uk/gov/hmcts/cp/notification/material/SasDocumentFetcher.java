package uk.gov.hmcts.cp.notification.material;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Downloads the document from the SAS link Material issued, on the shared bare HTTP client, which never
 * follows redirects. Nothing is added to the request — no credential, no system user, no correlation id —
 * so it carries only the link itself. The size cap is enforced
 * from {@code Content-Length} before reading and, when it is absent, by stopping the read as soon as the cap
 * is passed. A single deadline (connect + read timeout) bounds the whole download.
 */
@Component
public class SasDocumentFetcher {
    private static final int HTTP_OK = 200;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;
    private static final byte[] DISCARDED = new byte[0];

    private final HttpClient httpClient;
    private final long maxAttachmentBytes;
    private final Duration readTimeout;
    private final Duration deadline;

    public SasDocumentFetcher(
            @Qualifier(MaterialConfig.MATERIAL_HTTP_CLIENT) final HttpClient httpClient,
            final MaterialSettings settings,
            @Value("${cp.notification.blob.max-attachment-bytes}") final long maxAttachmentBytes) {
        this.httpClient = httpClient;
        this.maxAttachmentBytes = maxAttachmentBytes;
        this.readTimeout = settings.sasReadTimeout();
        this.deadline = settings.connectTimeout().plus(settings.sasReadTimeout());
    }

    public byte[] fetch(final URI sasLink) {
        final HttpRequest request = HttpRequest.newBuilder(sasLink).GET().timeout(readTimeout).build();
        final HttpResponse<byte[]> response = send(request);
        final int status = response.statusCode();
        if (status == HTTP_BAD_REQUEST) {
            throw MaterialAttachmentException.permanent(
                    "The Material document link was rejected by storage (HTTP " + status + ")", status);
        }
        if (status != HTTP_OK) {
            throw MaterialAttachmentException.retryable(
                    "The Material document could not be downloaded (HTTP " + status + ")", status);
        }
        return response.body();
    }

    private HttpResponse<byte[]> send(final HttpRequest request) {
        final CompletableFuture<HttpResponse<byte[]>> pending = httpClient.sendAsync(request, this::bodyFor);
        try {
            return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            pending.cancel(true);
            throw MaterialAttachmentException.retryable("The Material document was not downloaded in time", null, e);
        } catch (final ExecutionException e) {
            final DocumentTooLargeException tooLarge = tooLargeCause(e);
            if (tooLarge != null) {
                throw MaterialAttachmentException.permanent(tooLargeMessage(tooLarge), HTTP_PAYLOAD_TOO_LARGE);
            }
            throw MaterialAttachmentException.retryable(
                    "The Material document could not be downloaded", null, e.getCause());
        } catch (final InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw MaterialAttachmentException.retryable("The Material document download was interrupted", null, e);
        }
    }

    private BodySubscriber<byte[]> bodyFor(final ResponseInfo info) {
        final BodySubscriber<byte[]> subscriber;
        if (info.statusCode() == HTTP_OK) {
            final OptionalLong contentLength = info.headers().firstValueAsLong("Content-Length");
            final boolean declaredTooLarge = contentLength.isPresent() && contentLength.getAsLong() > maxAttachmentBytes;
            subscriber = declaredTooLarge
                    ? CappedBodySubscriber.rejectingDeclared(contentLength.getAsLong())
                    : CappedBodySubscriber.cappedAt(maxAttachmentBytes);
        } else {
            subscriber = BodySubscribers.replacing(DISCARDED);
        }
        return subscriber;
    }

    private String tooLargeMessage(final DocumentTooLargeException tooLarge) {
        return tooLarge.declaredBytes == null
                ? "The Material document passed the maximum attachment size of " + maxAttachmentBytes
                        + " bytes while downloading"
                : "The Material document declares " + tooLarge.declaredBytes
                        + " bytes, exceeding the maximum attachment size of " + maxAttachmentBytes + " bytes";
    }

    private static DocumentTooLargeException tooLargeCause(final Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof DocumentTooLargeException)) {
            cause = cause.getCause();
        }
        return (DocumentTooLargeException) cause;
    }

    private static final class DocumentTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient Long declaredBytes;

        private DocumentTooLargeException(final Long declaredBytes) {
            super("document exceeds the maximum attachment size", null, false, false);
            this.declaredBytes = declaredBytes;
        }
    }

    private static final class CappedBodySubscriber implements BodySubscriber<byte[]> {
        private final long maxBytes;
        private final Long declaredBytes;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        private CappedBodySubscriber(final long maxBytes, final Long declaredBytes) {
            this.maxBytes = maxBytes;
            this.declaredBytes = declaredBytes;
        }

        private static CappedBodySubscriber cappedAt(final long maxBytes) {
            return new CappedBodySubscriber(maxBytes, null);
        }

        private static CappedBodySubscriber rejectingDeclared(final long declaredBytes) {
            return new CappedBodySubscriber(0, declaredBytes);
        }

        @Override
        public void onSubscribe(final Flow.Subscription newSubscription) {
            this.subscription = newSubscription;
            if (declaredBytes == null) {
                newSubscription.request(Long.MAX_VALUE);
            } else {
                reject();
            }
        }

        @Override
        public void onNext(final List<ByteBuffer> items) {
            for (final ByteBuffer item : items) {
                if (!body.isDone()) {
                    append(item);
                }
            }
        }

        private void append(final ByteBuffer item) {
            if (buffer.size() + (long) item.remaining() > maxBytes) {
                reject();
            } else {
                final byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.writeBytes(chunk);
            }
        }

        @Override
        public void onError(final Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(buffer.toByteArray());
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        private void reject() {
            subscription.cancel();
            body.completeExceptionally(new DocumentTooLargeException(declaredBytes));
        }
    }
}
