package uk.gov.hmcts.cp.notification.command;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.UUID;

/**
 * The send-letter-notification command: a precompiled PDF letter held by the Material service. Legacy took the
 * {@code notificationId} from the REST path; on the queue it travels in the body with the rest of the command.
 */
@Builder
public record SendLetterCommand(

        @NotNull UUID notificationId,

        @NotBlank String letterUrl,

        String postage,

        String clientContext
) {
    private static final String FIRST_CLASS = "first";

    /**
     * Legacy parity: only {@code first} (any case) sends first class. Anything else, including no postage,
     * sends no postage to Gov.Notify, which then uses its default (second class).
     */
    @JsonIgnore
    public boolean isFirstClass() {
        return FIRST_CLASS.equalsIgnoreCase(postage);
    }
}
