package uk.gov.hmcts.cp.notification.messaging;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

import uk.gov.hmcts.cp.notification.result.NotificationValidationFailedEvent;
import uk.gov.hmcts.cp.notification.result.ResultEventPublisher;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static uk.gov.hmcts.cp.notification.result.NotificationValidationFailedEvent.PAYLOAD_VALIDATION_FAILED;

/**
 * Tells the originator that its command was rejected because the payload failed validation, by publishing a
 * {@link NotificationValidationFailedEvent} to the reply queue the command named. {@code details} uses the
 * Jakarta {@link ConstraintViolationException} form {@code field: message}: for a payload that could not be read
 * it is the parser's message, prefixed with the field when the parser names one; for a payload that was read but
 * is invalid it is every constraint violation, sorted by field so the same payload always gives the same text.
 * A failure to send the event is thrown to the caller, which abandons the command so Service Bus redelivers it.
 */
@Component
@RequiredArgsConstructor
public class ValidationFailureNotifier {

    private static final Comparator<ConstraintViolation<?>> BY_FIELD_THEN_MESSAGE = Comparator
            .<ConstraintViolation<?>, String>comparing(violation -> violation.getPropertyPath().toString())
            .thenComparing(ConstraintViolation::getMessage);

    private final ResultEventPublisher resultEventPublisher;

    public <T> void notifyValidationFailed(
            final String replyQueue, final Set<ConstraintViolation<T>> violations, final String originalPayload) {
        publish(replyQueue, describe(violations), originalPayload);
    }

    public void notifyUnreadable(
            final String replyQueue, final JacksonException cause, final String originalPayload) {
        publish(replyQueue, details(cause), originalPayload);
    }

    private void publish(final String replyQueue, final String details, final String originalPayload) {
        final NotificationValidationFailedEvent event = NotificationValidationFailedEvent.builder()
                .reason(PAYLOAD_VALIDATION_FAILED)
                .details(details)
                .originalEventPayload(originalPayload)
                .build();
        resultEventPublisher.publish(replyQueue, event);
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
