package uk.gov.hmcts.cp.notification.sender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

/**
 * Sends an email through the legacy notification-notify Azure Function ({@code fa-<env>-ccp<stack>-notifyatt}),
 * the Office 365 route for attachments Gov.Notify cannot take (ADR-004). Request, headers and response handling
 * mirror legacy {@code MicrosoftOffice365ClientService} / {@code HttpConnectionHelper}. A 200 means the function
 * has sent the email, so the result is final: there is no status to poll.
 */
@Component
public class Office365Client implements EmailClient {
    public static final String OCP_APIM_SUBSCRIPTION_KEY = "Ocp-Apim-Subscription-Key";

    private static final Logger LOG = LoggerFactory.getLogger(Office365Client.class);

    private static final int HTTP_OK = 200;

    private final HttpClient httpClient;
    private final Office365Settings settings;
    private final Office365RequestBodyFactory requestBodyFactory;
    private final Office365ResponseReader responseReader;

    public Office365Client(
            @Qualifier(Office365Config.OFFICE365_HTTP_CLIENT) final HttpClient httpClient,
            final Office365Settings settings,
            final Office365RequestBodyFactory requestBodyFactory,
            final Office365ResponseReader responseReader) {
        this.httpClient = httpClient;
        this.settings = settings;
        this.requestBodyFactory = requestBodyFactory;
        this.responseReader = responseReader;
    }

    @Override
    public SendResult send(final SendEmailRequest request) {
        final HttpRequest httpRequest = HttpRequest.newBuilder(settings.endpoint())
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyFactory.jsonFor(request)))
                .header("content-type", "application/json")
                .header(OCP_APIM_SUBSCRIPTION_KEY, settings.subscriptionKey())
                .timeout(settings.requestTimeout())
                .build();
        final HttpResponse<String> response = exchange(httpRequest);
        if (response.statusCode() != HTTP_OK) {
            LOG.warn("Office 365 rejected notification {} (http {})", request.notificationId(), response.statusCode());
            throw new Office365Exception(response.statusCode(),
                    "Office 365 responded with '" + withoutTrailingNewline(response.body()) + "'", null);
        }
        return responseReader.sentResult(request, response.body());
    }

    private HttpResponse<String> exchange(final HttpRequest httpRequest) {
        try {
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (final HttpTimeoutException e) {
            throw new Office365Exception(null, "Office 365 did not answer in time", e);
        } catch (final IOException e) {
            throw new Office365Exception(null, "Office 365 could not be reached", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Office365Exception(null, "Office 365 request was interrupted", e);
        }
    }

    // Legacy chomp(): one trailing newline is dropped from the function's error body.
    private static String withoutTrailingNewline(final String value) {
        String text = value == null ? "" : value;
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
