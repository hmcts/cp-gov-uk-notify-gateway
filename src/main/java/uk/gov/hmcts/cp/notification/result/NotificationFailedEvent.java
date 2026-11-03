package uk.gov.hmcts.cp.notification.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class NotificationFailedEvent implements ResultEvent {
    public static final String EVENT_NAME = "notification-failed";

    private final UUID notificationId;
    private final OffsetDateTime failedTime;
    private final String errorMessage;
    private final Integer statusCode;
    private final String clientContext;

    @Override
    public String eventName() {
        return EVENT_NAME;
    }
}
