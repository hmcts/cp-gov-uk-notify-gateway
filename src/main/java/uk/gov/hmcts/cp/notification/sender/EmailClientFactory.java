package uk.gov.hmcts.cp.notification.sender;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailClientFactory {
    // Gov.Notify rejects attachments over 2MB, so (per legacy) a larger attachment is routed to the
    // Office 365 send path instead. Matches legacy BYTE_LENGTH_2_MB. Legacy's recipient-domain route
    // (notify.email.sender.recipientDomainsToRouteThroughOffice365) is not ported (ADR-002 D14).
    /* default */ static final int GOV_NOTIFY_MAX_ATTACHMENT_2_MB = 2_097_152;

    private final GovNotifyClient govNotifyClient;
    private final Office365Client office365Sender;

    public EmailClient selectFor(final SendEmailRequest request) {
        final byte[] attachment = request.attachment();
        final boolean tooLargeForGovNotify = attachment != null && attachment.length > GOV_NOTIFY_MAX_ATTACHMENT_2_MB;
        return tooLargeForGovNotify ? office365Sender : govNotifyClient;
    }
}
