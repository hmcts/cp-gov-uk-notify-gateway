package uk.gov.hmcts.cp.notification.messaging;

import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import uk.gov.hmcts.cp.notification.command.SendLetterCommand;
import uk.gov.hmcts.cp.notification.service.NotificationIngestionService;

import java.util.Optional;
import java.util.Set;

/**
 * Consumes send-letter-notification commands from the letter command queue, handling them exactly as
 * {@link SendEmailConsumer} handles email: an unreadable or invalid command is dead-lettered, a valid one is
 * ingested and completed, and a failure to ingest abandons the message for redelivery.
 */
@Component
@RequiredArgsConstructor
public class SendLetterConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(SendLetterConsumer.class);

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final NotificationIngestionService ingestionService;

    public void processMessage(final ServiceBusReceivedMessageContext context) {
        final String messageId = context.getMessage().getMessageId();
        final String body = context.getMessage().getBody().toString();

        deserialise(body, messageId, context).ifPresent(command -> {
            final Set<ConstraintViolation<SendLetterCommand>> violations = validator.validate(command);
            if (violations.isEmpty()) {
                ingest(command, context, messageId);
            } else {
                LOG.warn("Dead-lettering invalid letter message {}: {}", messageId, violations);
                context.deadLetter();
            }
        });
    }

    private Optional<SendLetterCommand> deserialise(
            final String body, final String messageId, final ServiceBusReceivedMessageContext context) {
        Optional<SendLetterCommand> command = Optional.empty();
        try {
            command = Optional.of(objectMapper.readValue(body, SendLetterCommand.class));
        } catch (final JacksonException e) {
            LOG.warn("Dead-lettering unparseable letter message {} (cause: {})", messageId, e.getClass().getSimpleName());
            context.deadLetter();
        }
        return command;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void ingest(
            final SendLetterCommand command, final ServiceBusReceivedMessageContext context, final String messageId) {
        try {
            ingestionService.ingest(command, context.getMessage().getReplyTo());
            context.complete();
        } catch (final Exception e) {
            LOG.error("Transient failure processing letter message {} — abandoning for redelivery", messageId, e);
            context.abandon();
        }
    }

    public void processError(final ServiceBusErrorContext context) {
        LOG.error("Service Bus error on entity {}", context.getEntityPath(), context.getException());
    }
}
