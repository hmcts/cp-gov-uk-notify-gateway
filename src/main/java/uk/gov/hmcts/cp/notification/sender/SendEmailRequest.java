package uk.gov.hmcts.cp.notification.sender;

import lombok.Builder;

import java.util.Map;
import java.util.UUID;

import static uk.gov.hmcts.cp.notification.logging.Redaction.redacted;

@Builder
public record SendEmailRequest(
        UUID notificationId,
        UUID templateId,
        String emailAddress,
        Map<String, Object> personalisation,
        byte[] attachment,
        String attachmentFilename,
        String replyToAddress,
        UUID replyToAddressId) {

    /** Keeps personal data out of logs. */
    @Override
    public String toString() {
        return """
                SendEmailRequest[\
                notificationId=%s, \
                templateId=%s, \
                emailAddress=%s, \
                personalisation=%s, \
                attachment=%s, \
                attachmentFilename=%s, \
                replyToAddress=%s, \
                replyToAddressId=%s]"""
                .formatted(
                        notificationId,
                        templateId,
                        redacted(emailAddress),
                        redacted(personalisation),
                        redacted(attachment),
                        redacted(attachmentFilename),
                        redacted(replyToAddress),
                        replyToAddressId);
    }
}
