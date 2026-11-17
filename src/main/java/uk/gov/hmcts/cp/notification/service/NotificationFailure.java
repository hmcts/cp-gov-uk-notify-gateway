package uk.gov.hmcts.cp.notification.service;

import uk.gov.hmcts.cp.notification.logging.Channel;
import uk.gov.hmcts.cp.notification.logging.OutcomeReason;

import java.util.UUID;

/**
 * Why a notification failed: what is recorded with the FAILED status and sent in {@code notification-failed}, and
 * what the requesting team is alerted with. The channel comes from the task that failed, so nothing has to be read
 * back from the stored notification to log it.
 *
 * @param statusCode the status code of the failure, or {@code null} when there was none
 */
public record NotificationFailure(
        UUID notificationId,
        Channel channel,
        Integer statusCode,
        String errorMessage,
        OutcomeReason outcomeReason) {
}
