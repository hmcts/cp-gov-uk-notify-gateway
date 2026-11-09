package uk.gov.hmcts.cp.notification.sender;

import lombok.Builder;

import static uk.gov.hmcts.cp.notification.logging.Redaction.redacted;

/**
 * Outcome of a send. {@code sentOnSubmit} is {@code true} when the route has already delivered the email
 * (Office 365), so there is no status to poll; {@code false} for Gov.Notify, which is polled.
 */
@Builder
public record SendResult(
        String reference, String emailSubject, String emailBody, String replyToAddress, boolean sentOnSubmit) {

    /** Leaves out the email as sent, so it cannot reach a log. */
    @Override
    public String toString() {
        return "SendResult[reference=" + reference + ", emailSubject=" + redacted(emailSubject)
                + ", emailBody=" + redacted(emailBody) + ", replyToAddress=" + redacted(replyToAddress)
                + ", sentOnSubmit=" + sentOnSubmit + "]";
    }
}
