package uk.gov.hmcts.cp.notification.sender;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the JSON body the legacy notifyatt Azure Function expects, as legacy
 * {@code MicrosoftOffice365ClientService} does: empty strings and null values are left out, personalisation is
 * always present and the attachment is Base64-encoded.
 */
@Component
public class Office365RequestBodyFactory {
    private final ObjectMapper objectMapper;

    public Office365RequestBodyFactory(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String jsonFor(final SendEmailRequest request) {
        try {
            return objectMapper.writeValueAsString(body(request));
        } catch (final JacksonException e) {
            throw Office365Exception.unsendable("Office 365 request could not be built", e);
        }
    }

    private static Map<String, Object> body(final SendEmailRequest request) {
        final Map<String, Object> body = new LinkedHashMap<>();
        putIfPresent(body, "emailAddress", request.emailAddress());
        putIfPresent(body, "templateId", request.templateId() == null ? null : request.templateId().toString());
        putIfPresent(body, "reference", request.notificationId() == null ? null : request.notificationId().toString());
        body.put("personalisation", request.personalisation() == null ? Map.of() : request.personalisation());
        final Map<String, Object> attachment = new LinkedHashMap<>();
        if (request.attachmentFilename() != null) {
            attachment.put("filename", request.attachmentFilename());
        }
        attachment.put("content", request.attachment() == null
                ? "" : Base64.getEncoder().encodeToString(request.attachment()));
        body.put("attachment", attachment);
        putIfPresent(body, "replyToAddressId",
                request.replyToAddressId() == null ? null : request.replyToAddressId().toString());
        return body;
    }

    private static void putIfPresent(final Map<String, Object> body, final String key, final String value) {
        if (value != null && !value.isEmpty()) {
            body.put(key, value);
        }
    }
}
