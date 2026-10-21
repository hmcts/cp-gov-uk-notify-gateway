package uk.gov.hmcts.cp.notification.sender;

import lombok.Builder;

/**
 * Outcome of a send. {@code sentOnSubmit} is {@code true} when the route has already delivered the email
 * (Office 365), so there is no status to poll; {@code false} for Gov.Notify, which is polled.
 */
@Builder
public record SendResult(
        String reference, String emailSubject, String emailBody, String replyToAddress, boolean sentOnSubmit) {
}
