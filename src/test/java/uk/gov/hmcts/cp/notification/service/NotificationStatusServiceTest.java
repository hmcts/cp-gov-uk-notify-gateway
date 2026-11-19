package uk.gov.hmcts.cp.notification.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.KeyValuePair;

import uk.gov.hmcts.cp.notification.logging.CapturedLogs;
import uk.gov.hmcts.cp.notification.persistence.NotificationEntity;
import uk.gov.hmcts.cp.notification.persistence.NotificationRepository;
import uk.gov.hmcts.cp.notification.result.NotificationFailedEvent;
import uk.gov.hmcts.cp.notification.result.NotificationSentEvent;
import uk.gov.hmcts.cp.notification.result.ResultEventPublisher;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static ch.qos.logback.classic.Level.WARN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_NOT_FOUND;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.STATUS_UNKNOWN;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;
import static uk.gov.hmcts.cp.notification.integration.testdata.NotificationEntityFactory.aLetterNotificationEntity;
import static uk.gov.hmcts.cp.notification.integration.testdata.NotificationEntityFactory.aNotificationEntity;

@ExtendWith(MockitoExtension.class)
class NotificationStatusServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private Clock clock;
    @Mock
    private ResultEventPublisher resultEventPublisher;

    private static final NotificationEmailDetails EMAIL_DETAILS = new NotificationEmailDetails(
            "Your NCES extract", "Please find your report attached.", "noreply@justice.gov.uk");

    private NotificationStatusService service;

    @BeforeEach
    void createServiceWithMockedCollaborators() {
        service = new NotificationStatusService(notificationRepository, clock, resultEventPublisher);
    }

    @Test
    void marks_a_notification_sent() {
        final UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id))
                .thenReturn(Optional.of(aNotificationEntity().notificationId(id).build()));

        service.markSent(id, EMAIL_DETAILS);

        final ArgumentCaptor<NotificationEntity> saved = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("SENT");
    }

    @Test
    void marks_a_notification_sent_and_publishes_its_sent_event_when_recorded_in_a_new_transaction() {
        final UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aNotificationEntity()
                .notificationId(id)
                .resultQueue("originator-reply-queue")
                .build()));

        service.markSentInNewTransaction(id, EMAIL_DETAILS);

        final ArgumentCaptor<NotificationEntity> saved = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("SENT");
        verify(resultEventPublisher).publish(eq("originator-reply-queue"), any(NotificationSentEvent.class));
    }

    @Test
    void marks_a_notification_failed_with_status_code_and_error_message() {
        final UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id))
                .thenReturn(Optional.of(aNotificationEntity().notificationId(id).build()));

        service.markFailed(new NotificationFailure(id, EMAIL, 500, "provider rejected the send", PROVIDER_REJECTED));

        final ArgumentCaptor<NotificationEntity> saved = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().getStatusCode()).isEqualTo(500);
        assertThat(saved.getValue().getErrorMessage()).isEqualTo("provider rejected the send");
    }

    @Test
    void marks_a_notification_failed_and_logs_it_only_after_publishing_the_failure_event() {
        final UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aNotificationEntity()
                .notificationId(id)
                .resultQueue("originator-reply-queue")
                .build()));

        final List<Integer> logLinesWrittenWhenPublished = new ArrayList<>();

        try (CapturedLogs capturedLogs = captureLogsOf(NotificationStatusService.class)) {
            doAnswer(invocation -> {
                final List<ILoggingEvent> loggingEvents = capturedLogs.events();
                logLinesWrittenWhenPublished.add(loggingEvents.size());
                return null;
            }).when(resultEventPublisher).publish(eq("originator-reply-queue"), any(NotificationFailedEvent.class));

            service.markFailed(new NotificationFailure(id, EMAIL, 500, "provider rejected the send", PROVIDER_REJECTED));
        }

        assertThat(logLinesWrittenWhenPublished).containsExactly(0);
    }

    @Test
    void writes_no_failed_line_when_publishing_the_failure_event_fails() {
        final UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aNotificationEntity()
                .notificationId(id)
                .resultQueue("originator-reply-queue")
                .build()));
        doThrow(new IllegalStateException("Service Bus unavailable"))
                .when(resultEventPublisher).publish(eq("originator-reply-queue"), any(NotificationFailedEvent.class));
        final NotificationFailure notificationFailure =
                new NotificationFailure(id, EMAIL, 500, "provider rejected the send", PROVIDER_REJECTED);

        final List<ILoggingEvent> loggingEvents;
        try (CapturedLogs capturedLogs = captureLogsOf(NotificationStatusService.class)) {
            assertThatThrownBy(() -> service.markFailed(notificationFailure))
                    .isInstanceOf(IllegalStateException.class);
            loggingEvents = capturedLogs.events();
        }

        assertThat(loggingEvents).isEmpty();
    }

    @Test
    void fails_when_the_notification_row_is_missing() {
        final UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markSent(id, EMAIL_DETAILS)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishes_a_sent_result_event_to_the_persisted_reply_queue() {
        final UUID id = UUID.randomUUID();
        final OffsetDateTime sentAt = OffsetDateTime.of(2026, 10, 27, 14, 5, 0, 0, ZoneOffset.UTC);
        when(clock.offsetDateTime()).thenReturn(sentAt);
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aNotificationEntity()
                .notificationId(id)
                .resultQueue("originator-reply-queue")
                .clientContext("mi-reportdata")
                .build()));

        service.markSent(id, EMAIL_DETAILS);

        final ArgumentCaptor<NotificationSentEvent> event = ArgumentCaptor.forClass(NotificationSentEvent.class);
        verify(resultEventPublisher).publish(eq("originator-reply-queue"), event.capture());
        assertThat(event.getValue().getNotificationId()).isEqualTo(id);
        assertThat(event.getValue().getClientContext()).isEqualTo("mi-reportdata");
        assertThat(event.getValue().getEmailSubject()).isEqualTo("Your NCES extract");
        assertThat(event.getValue().getEmailBody()).isEqualTo("Please find your report attached.");
        assertThat(event.getValue().getReplyToAddress()).isEqualTo("noreply@justice.gov.uk");
        assertThat(event.getValue().getSentTime()).isEqualTo(sentAt);
        assertThat(event.getValue().getCompletedAt()).isEqualTo(sentAt);
    }

    @Test
    void publishes_a_failed_result_event_to_the_persisted_reply_queue() {
        final UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aNotificationEntity()
                .notificationId(id)
                .resultQueue("originator-reply-queue")
                .build()));

        service.markFailed(new NotificationFailure(id, EMAIL, 400, "provider rejected the send", PROVIDER_REJECTED));

        final ArgumentCaptor<NotificationFailedEvent> event = ArgumentCaptor.forClass(NotificationFailedEvent.class);
        verify(resultEventPublisher).publish(eq("originator-reply-queue"), event.capture());
        assertThat(event.getValue().getNotificationId()).isEqualTo(id);
        assertThat(event.getValue().getStatusCode()).isEqualTo(400);
        assertThat(event.getValue().getErrorMessage()).isEqualTo("provider rejected the send");
    }

    @Test
    void logs_a_failed_email_with_its_reply_queue_and_outcome_reason() {
        final UUID id = UUID.fromString("5a7e1c2d-3b4f-4e6a-9c8d-1f2e3d4c5b6a");
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aNotificationEntity()
                .notificationId(id)
                .resultQueue("sjp-notification-result")
                .build()));

        final ILoggingEvent loggingEvent;
        try (CapturedLogs capturedLogs = captureLogsOf(NotificationStatusService.class)) {
            service.markFailed(new NotificationFailure(id, EMAIL, 400, "provider rejected the send", PROVIDER_REJECTED));
            loggingEvent = onlyEventIn(capturedLogs);
        }

        assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
        final String message = loggingEvent.getFormattedMessage();
        assertThat(message).isEqualTo("""
                Notification '5a7e1c2d-3b4f-4e6a-9c8d-1f2e3d4c5b6a' for reply queue 'sjp-notification-result' \
                marked FAILED (statusCode '400'): the provider rejected the notification""");
        final Map<String, Object> fields = fieldsOf(loggingEvent);
        assertThat(fields).hasSize(4);
        assertThat(fields.get("replyQueue")).isEqualTo("sjp-notification-result");
        assertThat(fields.get("notificationOutcome")).isEqualTo("failed");
        assertThat(fields.get("outcomeReason")).isEqualTo("provider-rejected");
        assertThat(fields.get("notificationChannel")).isEqualTo("email");
    }

    @Test
    void logs_none_as_the_status_code_of_a_failure_that_had_none() {
        final UUID id = UUID.fromString("5a7e1c2d-3b4f-4e6a-9c8d-1f2e3d4c5b6a");
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aNotificationEntity()
                .notificationId(id)
                .resultQueue("sjp-notification-result")
                .build()));

        final ILoggingEvent loggingEvent;
        try (CapturedLogs capturedLogs = captureLogsOf(NotificationStatusService.class)) {
            service.markFailed(new NotificationFailure(id, EMAIL, null, "no status", STATUS_UNKNOWN));
            loggingEvent = onlyEventIn(capturedLogs);
        }

        final String message = loggingEvent.getFormattedMessage();
        assertThat(message).contains("marked FAILED (statusCode 'none')");
        final Map<String, Object> fields = fieldsOf(loggingEvent);
        assertThat(fields.get("outcomeReason")).isEqualTo("status-unknown");
    }

    @Test
    void logs_a_failed_letter_with_the_letter_channel() {
        final UUID id = UUID.fromString("6b8f2d3e-4c5a-4f7b-8d9e-2a3b4c5d6e7f");
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aLetterNotificationEntity()
                .notificationId(id)
                .resultQueue("dvla-notification-result")
                .build()));

        final ILoggingEvent loggingEvent;
        try (CapturedLogs capturedLogs = captureLogsOf(NotificationStatusService.class)) {
            service.markFailed(new NotificationFailure(
                    id, LETTER, 404, "Material has no such document (HTTP 404)", ATTACHMENT_NOT_FOUND));
            loggingEvent = onlyEventIn(capturedLogs);
        }

        final Map<String, Object> fields = fieldsOf(loggingEvent);
        assertThat(fields.get("replyQueue")).isEqualTo("dvla-notification-result");
        assertThat(fields.get("notificationOutcome")).isEqualTo("failed");
        assertThat(fields.get("outcomeReason")).isEqualTo("attachment-not-found");
        assertThat(fields.get("notificationChannel")).isEqualTo("letter");
    }

    @Test
    void marks_failed_publishes_and_logs_even_when_the_stored_notification_type_is_not_a_channel() {
        final UUID id = UUID.fromString("7c9a3e4f-5d6b-4a8c-9e0f-3b4c5d6e7f8a");
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aNotificationEntity()
                .notificationId(id)
                .notificationType("email")
                .resultQueue("sjp-notification-result")
                .build()));

        final ILoggingEvent loggingEvent;
        try (CapturedLogs capturedLogs = captureLogsOf(NotificationStatusService.class)) {
            service.markFailed(new NotificationFailure(id, EMAIL, 400, "provider rejected the send", PROVIDER_REJECTED));
            loggingEvent = onlyEventIn(capturedLogs);
        }

        final ArgumentCaptor<NotificationEntity> saved = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(saved.capture());
        final NotificationEntity savedNotification = saved.getValue();
        assertThat(savedNotification.getStatus()).isEqualTo("FAILED");
        verify(resultEventPublisher).publish(eq("sjp-notification-result"), any(NotificationFailedEvent.class));
        final Map<String, Object> fields = fieldsOf(loggingEvent);
        assertThat(fields.get("notificationChannel")).isEqualTo("email");
    }

    @Test
    void marks_a_letter_sent_and_publishes_a_sent_event_completed_when_it_was_recorded_with_no_email_fields() {
        final UUID id = UUID.randomUUID();
        final OffsetDateTime receivedAt = OffsetDateTime.of(2026, 10, 23, 9, 30, 15, 123_000_000, ZoneOffset.UTC);
        when(clock.offsetDateTime()).thenReturn(receivedAt);
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aLetterNotificationEntity()
                .notificationId(id)
                .resultQueue("originator-reply-queue")
                .clientContext("correspondence")
                .build()));

        service.markLetterSent(id);

        final ArgumentCaptor<NotificationEntity> saved = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("SENT");
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(receivedAt);

        final ArgumentCaptor<NotificationSentEvent> event = ArgumentCaptor.forClass(NotificationSentEvent.class);
        verify(resultEventPublisher).publish(eq("originator-reply-queue"), event.capture());
        assertThat(event.getValue().getNotificationId()).isEqualTo(id);
        assertThat(event.getValue().getSentTime()).isEqualTo(receivedAt);
        assertThat(event.getValue().getCompletedAt()).isEqualTo(receivedAt);
        assertThat(event.getValue().getClientContext()).isEqualTo("correspondence");
        assertThat(event.getValue().getSendToAddress()).isNull();
        assertThat(event.getValue().getEmailSubject()).isNull();
        assertThat(event.getValue().getEmailBody()).isNull();
        assertThat(event.getValue().getReplyToAddress()).isNull();
    }

    @Test
    void marks_a_letter_requeued_clearing_any_earlier_error_and_publishes_no_result_event() {
        final UUID id = UUID.randomUUID();
        final OffsetDateTime requeuedAt = OffsetDateTime.of(2026, 10, 23, 9, 30, 15, 0, ZoneOffset.UTC);
        when(clock.offsetDateTime()).thenReturn(requeuedAt);
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aLetterNotificationEntity()
                .notificationId(id)
                .resultQueue("originator-reply-queue")
                .statusCode(500)
                .errorMessage("earlier failure")
                .build()));

        service.markRequeued(id);

        final ArgumentCaptor<NotificationEntity> saved = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("REQUEUED");
        assertThat(saved.getValue().getStatusCode()).isNull();
        assertThat(saved.getValue().getErrorMessage()).isNull();
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(requeuedAt);
        verifyNoInteractions(resultEventPublisher);
    }

    @Test
    void routes_the_result_event_by_the_notifications_own_reply_queue_when_absent() {
        final UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aNotificationEntity()
                .notificationId(id)
                .resultQueue(null)
                .build()));

        service.markSent(id, EMAIL_DETAILS);

        verify(resultEventPublisher).publish(isNull(), any(NotificationSentEvent.class));
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
}
