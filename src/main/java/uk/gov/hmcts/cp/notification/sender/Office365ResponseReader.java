package uk.gov.hmcts.cp.notification.sender;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the notifyatt Azure Function's {@code 200} response into a {@link SendResult} that is already sent, as legacy
 * {@code EmailSender.getExtractedSendEmailResponseFromOffice365EmailResponse} does: the subject as returned, the
 * body as the text of the second line of {@code templateContent}, and the command's reply-to address or
 * {@code NOREPLY@noreply.com}. An unreadable response still means the email was sent (ADR-002 D13), so it gives
 * an empty subject and body rather than a failure.
 */
@Component
public class Office365ResponseReader {
    public static final String NO_REPLY_EMAIL_ADDRESS = "NOREPLY@noreply.com";

    private static final Logger LOG = LoggerFactory.getLogger(Office365ResponseReader.class);

    private final ObjectMapper objectMapper;

    public Office365ResponseReader(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SendResult sentResult(final SendEmailRequest request, final String responseBody) {
        String subject = "";
        String emailBody = "";
        try {
            final JsonNode data = objectMapper.readTree(responseBody);
            subject = text(data, "subject");
            emailBody = Jsoup.parse(templateBody(text(data, "templateContent"))).body().text();
        } catch (final JacksonException | IllegalArgumentException e) {
            LOG.warn("Office 365 accepted notification {} but its response could not be read — recording it SENT "
                    + "without a subject or body", request.notificationId());
        }
        return SendResult.builder()
                .reference(request.notificationId() == null ? null : request.notificationId().toString())
                .emailSubject(subject)
                .emailBody(emailBody)
                .replyToAddress(request.replyToAddress() == null ? NO_REPLY_EMAIL_ADDRESS : request.replyToAddress())
                .sentOnSubmit(true)
                .build();
    }

    private static String text(final JsonNode data, final String field) {
        final JsonNode node = data == null ? null : data.get(field);
        return node == null || node.isNull() ? "" : node.asString();
    }

    private static String templateBody(final String templateContent) {
        final String[] parts = templateContent.split("\n");
        return parts.length > 1 ? parts[1].trim() : "";
    }
}
