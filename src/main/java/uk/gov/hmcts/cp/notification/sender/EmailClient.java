package uk.gov.hmcts.cp.notification.sender;

import uk.gov.hmcts.cp.notification.logging.Dependency;

public interface EmailClient {
    SendResult send(SendEmailRequest request);

    Dependency dependency();
}
