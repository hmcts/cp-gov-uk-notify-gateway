package uk.gov.hmcts.cp.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.logging.NotificationOutcomeEvent;
import uk.gov.hmcts.cp.notification.logging.NotificationOutcomeLogger;
import uk.gov.hmcts.cp.notification.persistence.NotificationEntity;
import uk.gov.hmcts.cp.notification.persistence.NotificationRepository;
import uk.gov.hmcts.cp.notification.result.NotificationFailedEvent;
import uk.gov.hmcts.cp.notification.result.NotificationSentEvent;
import uk.gov.hmcts.cp.notification.result.ResultEventPublisher;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.NotificationOutcome.FAILED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_NOT_FOUND;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;
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
    @Mock
    private NotificationOutcomeLogger notificationOutcomeLogger;

    private static final NotificationEmailDetails EMAIL_DETAILS = new NotificationEmailDetails(
            "Your NCES extract", "Please find your report attached.", "noreply@justice.gov.uk");

    private NotificationStatusService service;

    @BeforeEach
    void createServiceWithMockedCollaborators() {
        service = new NotificationStatusService(
                notificationRepository, clock, resultEventPublisher, notificationOutcomeLogger);
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

        service.markFailed(id, 500, "provider rejected the send", PROVIDER_REJECTED);

        final ArgumentCaptor<NotificationEntity> saved = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().getStatusCode()).isEqualTo(500);
        assertThat(saved.getValue().getErrorMessage()).isEqualTo("provider rejected the send");
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

        service.markFailed(id, 400, "provider rejected the send", PROVIDER_REJECTED);

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

        service.markFailed(id, 400, "provider rejected the send", PROVIDER_REJECTED);

        final ArgumentCaptor<NotificationOutcomeEvent> notificationOutcomeEventCaptor =
                ArgumentCaptor.forClass(NotificationOutcomeEvent.class);
        final ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationOutcomeLogger).warn(notificationOutcomeEventCaptor.capture(), messageCaptor.capture());
        final NotificationOutcomeEvent notificationOutcomeEvent = notificationOutcomeEventCaptor.getValue();
        assertThat(notificationOutcomeEvent.notificationOutcome()).isEqualTo(FAILED);
        assertThat(notificationOutcomeEvent.outcomeReason()).isEqualTo(PROVIDER_REJECTED);
        assertThat(notificationOutcomeEvent.channel()).isEqualTo(EMAIL);
        assertThat(notificationOutcomeEvent.replyQueue()).isEqualTo("sjp-notification-result");
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Notification '5a7e1c2d-3b4f-4e6a-9c8d-1f2e3d4c5b6a' for reply queue"
                + " 'sjp-notification-result' failed: the provider rejected the notification");
    }

    @Test
    void logs_a_failed_letter_with_the_letter_channel() {
        final UUID id = UUID.fromString("6b8f2d3e-4c5a-4f7b-8d9e-2a3b4c5d6e7f");
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aLetterNotificationEntity()
                .notificationId(id)
                .resultQueue("dvla-notification-result")
                .build()));

        service.markFailed(id, 404, "Material has no such document (HTTP 404)", ATTACHMENT_NOT_FOUND);

        final ArgumentCaptor<NotificationOutcomeEvent> notificationOutcomeEventCaptor =
                ArgumentCaptor.forClass(NotificationOutcomeEvent.class);
        final ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationOutcomeLogger).warn(notificationOutcomeEventCaptor.capture(), messageCaptor.capture());
        final NotificationOutcomeEvent notificationOutcomeEvent = notificationOutcomeEventCaptor.getValue();
        assertThat(notificationOutcomeEvent.notificationOutcome()).isEqualTo(FAILED);
        assertThat(notificationOutcomeEvent.outcomeReason()).isEqualTo(ATTACHMENT_NOT_FOUND);
        assertThat(notificationOutcomeEvent.channel()).isEqualTo(LETTER);
        assertThat(notificationOutcomeEvent.replyQueue()).isEqualTo("dvla-notification-result");
        final String message = messageCaptor.getValue();
        assertThat(message).isEqualTo("Notification '6b8f2d3e-4c5a-4f7b-8d9e-2a3b4c5d6e7f' for reply queue"
                + " 'dvla-notification-result' failed: the attachment was not found");
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
}
