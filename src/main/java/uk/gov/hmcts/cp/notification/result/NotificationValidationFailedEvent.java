package uk.gov.hmcts.cp.notification.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

/**
 * Returned to the originator's reply queue when a command is rejected because its payload fails validation.
 * The command is not accepted, so there is no notification to report on: the originator correlates through
 * {@code originalEventPayload}, which is the command body exactly as it was received.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class NotificationValidationFailedEvent implements ResultEvent {
    public static final String EVENT_NAME = "notification-validation-failed";
    public static final String PAYLOAD_VALIDATION_FAILED = "PAYLOAD_VALIDATION_FAILED";

    private final String reason;
    private final String details;
    private final String originalEventPayload;

    @Override
    public String eventName() {
        return EVENT_NAME;
    }
}
