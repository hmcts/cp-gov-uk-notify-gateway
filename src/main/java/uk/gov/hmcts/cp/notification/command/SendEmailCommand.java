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
     * Keeps personal data out of logs. fileUri, materialUrl and clientContext are left out because blob paths and
     * Material links can carry case or person names.
     */
    @Override
    public String toString() {
        return """
                SendEmailCommand[\
                notificationId=%s, \
                templateId=%s, \
                sendToAddress=%s, \
                replyToAddress=%s, \
                personalisation=%s]"""
                .formatted(
                        notificationId,
                        templateId,
                        redacted(sendToAddress),
                        redacted(replyToAddress),
                        redacted(personalisation));
    }
}
