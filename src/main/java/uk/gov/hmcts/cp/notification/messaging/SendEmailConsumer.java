package uk.gov.hmcts.cp.notification.messaging;

import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.service.NotificationIngestionService;

/**
 * Consumes send-email-notification commands from the email command queue; how each message is settled is
 * {@link CommandMessageProcessor}'s job.
 */
@Component
@RequiredArgsConstructor
public class SendEmailConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(SendEmailConsumer.class);

    private final CommandMessageProcessor processor;
    private final NotificationIngestionService ingestionService;

    public void processMessage(final ServiceBusReceivedMessageContext context) {
        processor.process(context, SendEmailCommand.class, ingestionService::ingest);
    }

    public void processError(final ServiceBusErrorContext context) {
        LOG.error("Service Bus error on entity {}", context.getEntityPath(), context.getException());
    }
}
