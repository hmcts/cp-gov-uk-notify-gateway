package uk.gov.hmcts.cp.notification.messaging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.KeyValuePair;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.command.SendLetterCommand;
import uk.gov.hmcts.cp.notification.logging.CapturedLogs;
import uk.gov.hmcts.cp.notification.result.NotificationValidationFailedEvent;
import uk.gov.hmcts.cp.notification.result.ResultEvent;
import uk.gov.hmcts.cp.notification.result.ResultEventPublisher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static ch.qos.logback.classic.Level.WARN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommand;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.aSendLetterCommand;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;

@ExtendWith(MockitoExtension.class)
class ValidationFailureNotifierTest {

    private static final String REPLY_QUEUE = "steccm01.mi-reportdata-notification-result";
    private static final String ORIGINAL_PAYLOAD = "{\"sendToAddress\":\"\"}";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @Mock
    private ResultEventPublisher resultEventPublisher;

    private ValidationFailureNotifier notifier;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @BeforeEach
    void createNotifierWithMockedPublisher() {
        notifier = new ValidationFailureNotifier(resultEventPublisher);
    }

    @Test
    void should_publish_a_validation_failed_event_to_the_reply_queue_when_a_command_fails_validation() {
        final Set<ConstraintViolation<SendLetterCommand>> violations =
                validator.validate(aSendLetterCommand().letterUrl(null).build());

        notifier.notifyValidationFailed(REPLY_QUEUE, LETTER, violations, ORIGINAL_PAYLOAD);

        final NotificationValidationFailedEvent event = publishedEvent();
        assertThat(event.eventName()).isEqualTo("notification-validation-failed");
        assertThat(event.getReason()).isEqualTo("PAYLOAD_VALIDATION_FAILED");
        assertThat(event.getDetails()).isEqualTo("letterUrl: must not be blank");
        assertThat(event.getOriginalEventPayload()).isEqualTo(ORIGINAL_PAYLOAD);
    }

    @Test
    void should_list_every_violation_sorted_by_field_when_a_command_has_several() {
        final Set<ConstraintViolation<SendEmailCommand>> violations = validator.validate(aSendEmailCommand()
                .templateId(null)
                .sendToAddress(null)
                .notificationId(null)
                .build());

        notifier.notifyValidationFailed(REPLY_QUEUE, EMAIL, violations, ORIGINAL_PAYLOAD);

        assertThat(publishedEvent().getDetails()).isEqualTo(
                "notificationId: must not be null, sendToAddress: must not be blank, templateId: must not be null");
    }

    @Test
    void should_name_the_field_when_a_command_cannot_be_read_because_a_field_has_the_wrong_type() {
        final String payload = "{\"notificationId\":\"" + UUID.randomUUID() + "\",\"templateId\":\"not-a-uuid\"}";

        notifier.notifyUnreadable(REPLY_QUEUE, EMAIL, readFailure(payload, SendEmailCommand.class), payload);

        final NotificationValidationFailedEvent event = publishedEvent();
        assertThat(event.getReason()).isEqualTo("PAYLOAD_VALIDATION_FAILED");
        assertThat(event.getDetails()).startsWith("templateId: ").contains("not-a-uuid");
        assertThat(event.getOriginalEventPayload()).isEqualTo(payload);
    }

    @Test
    void should_report_the_parser_message_when_a_command_is_not_valid_json() {
        final String payload = "this is not json";
        final JacksonException failure = readFailure(payload, SendLetterCommand.class);

        notifier.notifyUnreadable(REPLY_QUEUE, LETTER, failure, payload);

        final NotificationValidationFailedEvent event = publishedEvent();
        assertThat(event.getDetails()).isEqualTo(failure.getOriginalMessage());
        assertThat(event.getOriginalEventPayload()).isEqualTo(payload);
    }

    @Test
    void should_log_an_invalid_request_with_its_reply_queue_once_the_rejection_is_published() {
        final Set<ConstraintViolation<SendLetterCommand>> violations =
                validator.validate(aSendLetterCommand().letterUrl(null).build());

        final ILoggingEvent loggingEvent;
        try (CapturedLogs capturedLogs = captureLogsOf(ValidationFailureNotifier.class)) {
            notifier.notifyValidationFailed(REPLY_QUEUE, LETTER, violations, ORIGINAL_PAYLOAD);
            loggingEvent = onlyEventIn(capturedLogs);
        }

        assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
        final String message = loggingEvent.getFormattedMessage();
        assertThat(message).isEqualTo("""
                Rejected the letter request for reply queue 'steccm01.mi-reportdata-notification-result': \
                the request failed validation""");
        final Map<String, Object> fields = fieldsOf(loggingEvent);
        assertThat(fields).hasSize(4);
        assertThat(fields.get("replyQueue")).isEqualTo(REPLY_QUEUE);
        assertThat(fields.get("notificationOutcome")).isEqualTo("rejected");
        assertThat(fields.get("outcomeReason")).isEqualTo("invalid-request");
        assertThat(fields.get("notificationChannel")).isEqualTo("letter");
    }

    @Test
    void should_log_an_unreadable_request_with_its_reply_queue_once_the_rejection_is_published() {
        final String payload = "this is not json";
        final JacksonException failure = readFailure(payload, SendEmailCommand.class);

        final ILoggingEvent loggingEvent;
        try (CapturedLogs capturedLogs = captureLogsOf(ValidationFailureNotifier.class)) {
            notifier.notifyUnreadable(REPLY_QUEUE, EMAIL, failure, payload);
            loggingEvent = onlyEventIn(capturedLogs);
        }

        final String message = loggingEvent.getFormattedMessage();
        assertThat(message).isEqualTo("""
                Rejected the email request for reply queue 'steccm01.mi-reportdata-notification-result': \
                the request could not be read""");
        final Map<String, Object> fields = fieldsOf(loggingEvent);
        assertThat(fields.get("replyQueue")).isEqualTo(REPLY_QUEUE);
        assertThat(fields.get("notificationOutcome")).isEqualTo("rejected");
        assertThat(fields.get("outcomeReason")).isEqualTo("unreadable-request");
        assertThat(fields.get("notificationChannel")).isEqualTo("email");
    }

    @Test
    void should_not_log_the_rejection_when_it_could_not_be_published() {
        final String payload = "this is not json";
        final JacksonException failure = readFailure(payload, SendEmailCommand.class);
        doThrow(new IllegalStateException("Service Bus unavailable"))
                .when(resultEventPublisher).publish(eq(REPLY_QUEUE), any(ResultEvent.class));

        final List<ILoggingEvent> loggingEvents;
        try (CapturedLogs capturedLogs = captureLogsOf(ValidationFailureNotifier.class)) {
            assertThatThrownBy(() -> notifier.notifyUnreadable(REPLY_QUEUE, EMAIL, failure, payload))
                    .isInstanceOf(IllegalStateException.class);
            loggingEvents = capturedLogs.events();
        }

        assertThat(loggingEvents).isEmpty();
    }

    private ILoggingEvent onlyEventIn(final CapturedLogs capturedLogs) {
        final List<ILoggingEvent> loggingEvents = capturedLogs.events();
        assertThat(loggingEvents).hasSize(1);
        return loggingEvents.getFirst();
    }

    private Map<String, Object> fieldsOf(final ILoggingEvent loggingEvent) {
        final Map<String, Object> fields = new HashMap<>();
        final List<KeyValuePair> keyValuePairs = loggingEvent.getKeyValuePairs();
        for (final KeyValuePair keyValuePair : keyValuePairs) {
            fields.put(keyValuePair.key, keyValuePair.value);
        }
        return fields;
    }

    private static JacksonException readFailure(final String payload, final Class<?> type) {
        return catchThrowableOfType(JacksonException.class, () -> MAPPER.readValue(payload, type));
    }

    private NotificationValidationFailedEvent publishedEvent() {
        final ArgumentCaptor<ResultEvent> event = ArgumentCaptor.forClass(ResultEvent.class);
        verify(resultEventPublisher).publish(eq(REPLY_QUEUE), event.capture());
        assertThat(event.getValue()).isInstanceOf(NotificationValidationFailedEvent.class);
        return (NotificationValidationFailedEvent) event.getValue();
    }
}
