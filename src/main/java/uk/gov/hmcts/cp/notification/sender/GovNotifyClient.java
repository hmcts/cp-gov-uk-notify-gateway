package uk.gov.hmcts.cp.notification.sender;

import org.springframework.stereotype.Component;

import uk.gov.service.notify.LetterResponse;
import uk.gov.service.notify.Notification;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.NotificationClientException;
import uk.gov.service.notify.SendEmailResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class GovNotifyClient implements EmailClient {
    private static final String MATERIAL_URL = "material_url";
    private static final String FIRST_CLASS_POSTAGE = "first";

    private final NotificationClient notificationClient;

    public GovNotifyClient(final NotificationClient notificationClient) {
        this.notificationClient = notificationClient;
    }

    @Override
    public SendResult send(final SendEmailRequest request) {
        try {
            final SendEmailResponse response = notificationClient.sendEmail(
                    request.templateId() == null ? null : request.templateId().toString(),
                    request.emailAddress(),
                    personalisation(request),
                    request.notificationId() == null ? null : request.notificationId().toString(),
                    emailReplyToId(request));
            return SendResult.builder()
                    .reference(response.getNotificationId().toString())
                    .emailSubject(response.getSubject())
                    .emailBody(response.getBody())
                    .replyToAddress(fromEmail(response))
                    .build();
        } catch (final NotificationClientException e) {
            throw new GovNotifyException(e.getHttpResult(), e.getMessage(), e);
        }
    }

    /**
     * Sends a precompiled PDF letter with the CPP notification id as its reference, as legacy did, and returns
     * Gov.Notify's id for the letter, which is what the status poll asks about. A first-class letter says so;
     * any other letter sends no postage, so Gov.Notify uses its default (second class).
     */
    public String sendPrecompiledLetter(final UUID notificationId, final byte[] pdf, final boolean firstClass) {
        // The document is already in memory (the Material download buffers it), so there is nothing to close.
        final InputStream document = new ByteArrayInputStream(pdf);
        try {
            final LetterResponse response = firstClass
                    ? notificationClient.sendPrecompiledLetterWithInputStream(
                            notificationId.toString(), document, FIRST_CLASS_POSTAGE)
                    : notificationClient.sendPrecompiledLetterWithInputStream(notificationId.toString(), document);
            return response.getNotificationId().toString();
        } catch (final NotificationClientException e) {
            throw new GovNotifyException(e.getHttpResult(), e.getMessage(), e);
        }
    }

    public NotificationStatus checkStatus(final String reference) {
        try {
            final Notification notification = notificationClient.getNotificationById(reference);
            return NotificationStatus.fromStatus(notification.getStatus());
        } catch (final NotificationClientException e) {
            throw new GovNotifyException(e.getHttpResult(), e.getMessage(), e);
        }
    }

    private static Map<String, Object> personalisation(final SendEmailRequest request) {
        final Map<String, Object> personalisation = new HashMap<>();
        if (request.personalisation() != null) {
            personalisation.putAll(request.personalisation());
        }
        final byte[] attachment = request.attachment();
        if (attachment != null && attachment.length > 0) {
            try {
                personalisation.put(MATERIAL_URL,
                        NotificationClient.prepareUpload(attachment, request.attachmentFilename()));
            } catch (final NotificationClientException e) {
                throw new GovNotifyException(e.getHttpResult(), e.getMessage(), e);
            }
        }
        return personalisation;
    }


    private static String emailReplyToId(final SendEmailRequest request) {
        return request.replyToAddressId() == null ? "" : request.replyToAddressId().toString();
    }

    private static String fromEmail(final SendEmailResponse response) {
        return response.getFromEmail().orElse(null);
    }
}
