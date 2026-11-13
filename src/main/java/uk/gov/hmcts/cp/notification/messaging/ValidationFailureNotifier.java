package uk.gov.hmcts.cp.notification.messaging;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

import uk.gov.hmcts.cp.notification.logging.Channel;
import uk.gov.hmcts.cp.notification.logging.NotificationOutcomeEvent;
import uk.gov.hmcts.cp.notification.logging.NotificationOutcomeLogger;
import uk.gov.hmcts.cp.notification.logging.OutcomeReason;
import uk.gov.hmcts.cp.notification.result.NotificationValidationFailedEvent;
import uk.gov.hmcts.cp.notification.result.ResultEventPublisher;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static uk.gov.hmcts.cp.notification.logging.NotificationOutcome.REJECTED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.INVALID_REQUEST;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.UNREADABLE_REQUEST;
import static uk.gov.hmcts.cp.notification.result.NotificationValidationFailedEvent.PAYLOAD_VALIDATION_FAILED;

/**
 * Tells the originator that its command was rejected because the payload failed validation, by publishing a
 * {@link NotificationValidationFailedEvent} to the reply queue the command named. {@code details} uses the
 * Jakarta {@link ConstraintViolationException} form {@code field: message}: for a payload that could not be read
 * it is the parser's message, prefixed with the field when the parser names one; for a payload that was read but
 * is invalid it is every constraint violation, sorted by field so the same payload always gives the same text.
 * A failure to send the event is thrown to the caller, which abandons the command so Service Bus redelivers it.
 * Once the event is sent, the rejection is logged with the reply queue and an {@link OutcomeReason}, so that an
 * alert can tell the team that owns the reply queue (PEG-3718); the log line holds neither the payload nor the
 * details, which may contain a recipient's address.
 */
@Component
@RequiredArgsConstructor
public class ValidationFailureNotifier {

    private static final Comparator<ConstraintViolation<?>> BY_FIELD_THEN_MESSAGE = Comparator
            .<ConstraintViolation<?>, String>comparing(violation -> violation.getPropertyPath().toString())
            .thenComparing(ConstraintViolation::getMessage);

    private final ResultEventPublisher resultEventPublisher;
    private final NotificationOutcomeLogger notificationOutcomeLogger;

    public <T> void notifyValidationFailed(
            final String replyQueue, final Channel channel, final Set<ConstraintViolation<T>> violations,
            final String originalPayload) {
        final String details = describe(violations);
        publish(replyQueue, details, originalPayload);
        logRejection(replyQueue, channel, INVALID_REQUEST);
    }

    public void notifyUnreadable(
            final String replyQueue, final Channel channel, final JacksonException cause,
            final String originalPayload) {
        final String details = details(cause);
        publish(replyQueue, details, originalPayload);
        logRejection(replyQueue, channel, UNREADABLE_REQUEST);
    }

    private void publish(final String replyQueue, final String details, final String originalPayload) {
        final NotificationValidationFailedEvent event = NotificationValidationFailedEvent.builder()
                .reason(PAYLOAD_VALIDATION_FAILED)
                .details(details)
                .originalEventPayload(originalPayload)
                .build();
        resultEventPublisher.publish(replyQueue, event);
    }

    private void logRejection(final String replyQueue, final Channel channel, final OutcomeReason outcomeReason) {
        final NotificationOutcomeEvent notificationOutcomeEvent =
                new NotificationOutcomeEvent(REJECTED, outcomeReason, channel, replyQueue);
        final String channelValue = channel.getFieldValue();
        final String reasonDescription = outcomeReason.getDescription();
        final String message = format("Rejected the %s request for reply queue '%s': %s",
                channelValue, replyQueue, reasonDescription);
        notificationOutcomeLogger.warn(notificationOutcomeEvent, message);
    }

    /**
     * Every violation as {@code field: message}, sorted by field. Holds no submitted values while the constraint
     * messages stay the standard ones (no {@code ${validatedValue}}), so it is also what is logged.
     */
    /* default */ static <T> String describe(final Set<ConstraintViolation<T>> violations) {
        final Set<ConstraintViolation<?>> sorted = new TreeSet<>(BY_FIELD_THEN_MESSAGE);
        sorted.addAll(violations);
        return new ConstraintViolationException(sorted).getMessage();
    }

    private static String details(final JacksonException cause) {
        final String field = cause.getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        return field.isEmpty() ? cause.getOriginalMessage() : field + ": " + cause.getOriginalMessage();
    }
}
