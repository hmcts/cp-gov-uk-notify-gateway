package uk.gov.hmcts.cp.notification.command;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.Map;
import java.util.UUID;

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
}
