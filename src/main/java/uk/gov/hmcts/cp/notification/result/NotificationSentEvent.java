package uk.gov.hmcts.cp.notification.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class NotificationSentEvent implements ResultEvent {
    public static final String EVENT_NAME = "notification-sent";

    private final UUID notificationId;
    private final OffsetDateTime sentTime;
    private final OffsetDateTime completedAt;
    private final String sendToAddress;
    private final String replyToAddress;
    private final String emailSubject;
    private final String emailBody;
    private final String clientContext;

    @Override
    public String eventName() {
        return EVENT_NAME;
    }
}
