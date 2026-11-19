package uk.gov.hmcts.cp.notification.sender;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.hmcts.cp.notification.logging.LogField.CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.LogField.DEPENDENCY;
import static uk.gov.hmcts.cp.notification.logging.LogField.OPERATION;
import static uk.gov.hmcts.cp.notification.logging.LogField.OUTCOME;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.logging.CallOutcome;
import uk.gov.hmcts.cp.notification.logging.Channel;
import uk.gov.hmcts.cp.notification.logging.Dependency;
import uk.gov.hmcts.cp.notification.logging.Operation;

import java.util.UUID;

/**
 * Sends an email through the client that can take it, and logs that it was sent with the fields the provider
 * outage alert counts (PEG-3720).
 */
@Component
@RequiredArgsConstructor
public class EmailSender {
    private final Logger logger = getLogger(EmailSender.class);

    private final SendEmailRequestMapper sendEmailRequestMapper;
    private final EmailClientFactory emailClientFactory;

    public SendResult sendEmail(final SendEmailCommand command, final Attachment attachment) {
        final SendEmailRequest request = sendEmailRequestMapper.requestFor(command, attachment);
        final EmailClient emailClient = emailClientFactory.selectFor(request);
        final SendResult sendResult = emailClient.send(request);
        final UUID notificationId = command.notificationId();
        final Dependency dependency = emailClient.dependency();
        final String dependencyName = dependency.getFieldValue();
        final String reference = sendResult.reference();
        final String message = format(
                "Sent notification '%s' via '%s' (reference '%s')", notificationId, dependencyName, reference);
        logger.atInfo()
                .addKeyValue(DEPENDENCY, dependencyName)
                .addKeyValue(OPERATION, Operation.SEND.getFieldValue())
                .addKeyValue(CHANNEL, Channel.EMAIL.getFieldValue())
                .addKeyValue(OUTCOME, CallOutcome.SUCCEEDED.getFieldValue())
                .log(message);
        return sendResult;
    }
}
