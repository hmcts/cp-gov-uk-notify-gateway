package uk.gov.hmcts.cp.notification.messaging;

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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.command.SendLetterCommand;
import uk.gov.hmcts.cp.notification.logging.NotificationOutcomeEvent;
import uk.gov.hmcts.cp.notification.logging.NotificationOutcomeLogger;
import uk.gov.hmcts.cp.notification.result.NotificationValidationFailedEvent;
import uk.gov.hmcts.cp.notification.result.ResultEvent;
import uk.gov.hmcts.cp.notification.result.ResultEventPublisher;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommand;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.aSendLetterCommand;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.NotificationOutcome.REJECTED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.INVALID_REQUEST;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.UNREADABLE_REQUEST;

@ExtendWith(MockitoExtension.class)
class ValidationFailureNotifierTest {

    private static final String REPLY_QUEUE = "steccm01.mi-reportdata-notification-result";
    private static final String ORIGINAL_PAYLOAD = "{\"sendToAddress\":\"\"}";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @Mock
    private ResultEventPublisher resultEventPublisher;
    @Mock
    private NotificationOutcomeLogger notificationOutcomeLogger;

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
    void createNotifierWithMockedPublisherAndLogger() {
        notifier = new ValidationFailureNotifier(resultEventPublisher, notificationOutcomeLogger);
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

        notifier.notifyValidationFailed(REPLY_QUEUE, LETTER, violations, ORIGINAL_PAYLOAD);

        final ArgumentCaptor<NotificationOutcomeEvent> notificationOutcomeEventCaptor =
                ArgumentCaptor.forClass(NotificationOutcomeEvent.class);
        final ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationOutcomeLogger).warn(notificationOutcomeEventCaptor.capture(), messageCaptor.capture());
        final NotificationOutcomeEvent notificationOutcomeEvent = notificationOutcomeEventCaptor.getValue();
        assertThat(notificationOutcomeEvent.notificationOutcome()).isEqualTo(REJECTED);
        assertThat(notificationOutcomeEvent.outcomeReason()).isEqualTo(INVALID_REQUEST);
        assertThat(notificationOutcomeEvent.channel()).isEqualTo(LETTER);
        assertThat(notificationOutcomeEvent.replyQueue()).isEqualTo(REPLY_QUEUE);
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Rejected the letter request for reply queue"
                + " 'steccm01.mi-reportdata-notification-result': the request failed validation");
    }

    @Test
    void should_log_an_unreadable_request_with_its_reply_queue_once_the_rejection_is_published() {
        final String payload = "this is not json";
        final JacksonException failure = readFailure(payload, SendEmailCommand.class);

        notifier.notifyUnreadable(REPLY_QUEUE, EMAIL, failure, payload);

        final ArgumentCaptor<NotificationOutcomeEvent> notificationOutcomeEventCaptor =
                ArgumentCaptor.forClass(NotificationOutcomeEvent.class);
        final ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationOutcomeLogger).warn(notificationOutcomeEventCaptor.capture(), messageCaptor.capture());
        final NotificationOutcomeEvent notificationOutcomeEvent = notificationOutcomeEventCaptor.getValue();
        assertThat(notificationOutcomeEvent.notificationOutcome()).isEqualTo(REJECTED);
        assertThat(notificationOutcomeEvent.outcomeReason()).isEqualTo(UNREADABLE_REQUEST);
        assertThat(notificationOutcomeEvent.channel()).isEqualTo(EMAIL);
        assertThat(notificationOutcomeEvent.replyQueue()).isEqualTo(REPLY_QUEUE);
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Rejected the email request for reply queue"
                + " 'steccm01.mi-reportdata-notification-result': the request could not be read");
    }

    @Test
    void should_not_log_the_rejection_when_it_could_not_be_published() {
        final String payload = "this is not json";
        final JacksonException failure = readFailure(payload, SendEmailCommand.class);
        doThrow(new IllegalStateException("Service Bus unavailable"))
                .when(resultEventPublisher).publish(eq(REPLY_QUEUE), any(ResultEvent.class));

        assertThatThrownBy(() -> notifier.notifyUnreadable(REPLY_QUEUE, EMAIL, failure, payload))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(notificationOutcomeLogger);
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
