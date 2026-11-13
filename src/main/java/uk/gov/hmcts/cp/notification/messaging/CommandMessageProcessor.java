package uk.gov.hmcts.cp.notification.messaging;

import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import uk.gov.hmcts.cp.notification.logging.Channel;

import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Settles one command message for {@link SendEmailConsumer} and {@link SendLetterConsumer}:
 * <ul>
 *   <li>no {@code ReplyTo}: dead-lettered unread (reason {@value #MISSING_REPLY_TO}), as no outcome could be
 *       returned;</li>
 *   <li>body unreadable or invalid: a {@code notification-validation-failed} event is sent to {@code ReplyTo} and
 *       the message completed; nothing is recorded;</li>
 *   <li>valid: handed to ingestion and completed;</li>
 *   <li>any failure to ingest or to send the rejection: abandoned, so Service Bus redelivers it and dead-letters it
 *       once the queue's max delivery count is reached.</li>
 * </ul>
 * No database work happens before a command has passed every check.
 */
@Component
@RequiredArgsConstructor
public class CommandMessageProcessor {
    public static final String MISSING_REPLY_TO = "MissingReplyTo";

    private static final Logger LOG = LoggerFactory.getLogger(CommandMessageProcessor.class);

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final ValidationFailureNotifier validationFailureNotifier;

    public <T> void process(
            final ServiceBusReceivedMessageContext context, final Class<T> commandType, final Channel channel,
            final BiConsumer<T, String> ingest) {
        final String messageId = context.getMessage().getMessageId();
        final String replyTo = context.getMessage().getReplyTo();
        if (StringUtils.hasText(replyTo)) {
            final String body = context.getMessage().getBody().toString();
            final Inbound inbound = new Inbound(context, messageId, replyTo, channel);
            read(body, commandType, inbound).ifPresent(command -> validateAndIngest(command, body, ingest, inbound));
        } else {
            LOG.warn("Dead-lettering {} {} with no ReplyTo: the outcome could not be returned",
                    commandType.getSimpleName(), messageId);
            context.deadLetter(new DeadLetterOptions()
                    .setDeadLetterReason(MISSING_REPLY_TO)
                    .setDeadLetterErrorDescription("The command has no ReplyTo, so no outcome could be returned"));
        }
    }

    private <T> Optional<T> read(final String body, final Class<T> commandType, final Inbound inbound) {
        Optional<T> command = Optional.empty();
        try {
            command = Optional.of(objectMapper.readValue(body, commandType));
        } catch (final JacksonException e) {
            LOG.warn("Rejecting unreadable {} {} (cause: {})",
                    commandType.getSimpleName(), inbound.messageId(), e.getClass().getSimpleName());
            reject(inbound, () -> validationFailureNotifier.notifyUnreadable(inbound.replyTo(), inbound.channel(), e, body));
        }
        return command;
    }

    private <T> void validateAndIngest(
            final T command, final String body, final BiConsumer<T, String> ingest, final Inbound inbound) {
        final Set<ConstraintViolation<T>> violations = validator.validate(command);
        if (violations.isEmpty()) {
            settle(inbound, () -> ingest.accept(command, inbound.replyTo()), "ingesting");
        } else {
            LOG.warn("Rejecting invalid {} {}: {}",
                    command.getClass().getSimpleName(), inbound.messageId(),
                    ValidationFailureNotifier.describe(violations));
            reject(inbound,
                    () -> validationFailureNotifier.notifyValidationFailed(
                            inbound.replyTo(), inbound.channel(), violations, body));
        }
    }

    private void reject(final Inbound inbound, final Runnable reportRejection) {
        settle(inbound, reportRejection, "returning the rejection of");
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void settle(final Inbound inbound, final Runnable work, final String activity) {
        boolean done = false;
        try {
            work.run();
            done = true;
        } catch (final Exception e) {
            LOG.error("Transient failure {} message {} — abandoning for redelivery",
                    activity, inbound.messageId(), e);
        }
        if (done) {
            inbound.context().complete();
        } else {
            inbound.context().abandon();
        }
    }

    private record Inbound(
            ServiceBusReceivedMessageContext context, String messageId, String replyTo, Channel channel) {
    }
}
