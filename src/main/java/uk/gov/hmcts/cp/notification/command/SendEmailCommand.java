package uk.gov.hmcts.cp.notification.command;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.Map;
import java.util.UUID;

import static uk.gov.hmcts.cp.notification.logging.Redaction.redacted;

@Builder
public record SendEmailCommand(

        @NotNull UUID notificationId,

        @NotNull UUID templateId,

        @NotBlank String sendToAddress,

        String fileUri,

        String materialUrl,

        @Email String replyToAddress,

        UUID replyToAddressId,

        Map<String, Object> personalisation,

        String clientContext
) {
    /**
     * Keeps personal data out of logs: the reply-to address and personalisation are redacted (the recipient is
     * shown, so a send can be traced), and
     * fileUri, materialUrl, replyToAddressId and clientContext are left out on purpose, because blob paths and
     * Material links can carry case or person names. Do not add them back.
     */
    @Override
    public String toString() {
        return "SendEmailCommand[notificationId=" + notificationId + ", templateId=" + templateId
                + ", sendToAddress=" + sendToAddress + ", replyToAddress=" + redacted(replyToAddress)
                + ", personalisation=" + redacted(personalisation) + "]";
    }
}
