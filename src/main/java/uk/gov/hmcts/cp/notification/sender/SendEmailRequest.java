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

    /**
     * Keeps personal data out of logs: the personalisation, attachment and its filename, and the reply-to address
     * are redacted. The recipient is shown, so a send can be traced.
     */
    @Override
    public String toString() {
        return "SendEmailRequest[notificationId=" + notificationId + ", templateId=" + templateId
                + ", emailAddress=" + emailAddress + ", personalisation=" + redacted(personalisation)
                + ", attachment=" + redacted(attachment) + ", attachmentFilename=" + redacted(attachmentFilename)
                + ", replyToAddress=" + redacted(replyToAddress) + ", replyToAddressId=" + replyToAddressId + "]";
    }
}
