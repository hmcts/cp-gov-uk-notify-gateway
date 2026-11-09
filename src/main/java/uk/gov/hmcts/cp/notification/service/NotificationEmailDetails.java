package uk.gov.hmcts.cp.notification.service;

import static uk.gov.hmcts.cp.notification.logging.Redaction.redacted;

public record NotificationEmailDetails(String emailSubject, String emailBody, String replyToAddress) {

    /** Leaves out the email as sent, so it cannot reach a log. */
    @Override
    public String toString() {
        return "NotificationEmailDetails[emailSubject=" + redacted(emailSubject) + ", emailBody=" + redacted(emailBody)
                + ", replyToAddress=" + redacted(replyToAddress) + "]";
    }
}
