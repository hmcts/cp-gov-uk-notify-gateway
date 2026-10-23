package uk.gov.hmcts.cp.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    void setUp() {
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

        service.markFailed(id, 500, "provider rejected the send");

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
    }

    @Test
    void publishes_a_failed_result_event_to_the_persisted_reply_queue() {
        final UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(aNotificationEntity()
                .notificationId(id)
                .resultQueue("originator-reply-queue")
                .build()));

        service.markFailed(id, 400, "provider rejected the send");

        final ArgumentCaptor<NotificationFailedEvent> event = ArgumentCaptor.forClass(NotificationFailedEvent.class);
        verify(resultEventPublisher).publish(eq("originator-reply-queue"), event.capture());
        assertThat(event.getValue().getNotificationId()).isEqualTo(id);
        assertThat(event.getValue().getStatusCode()).isEqualTo(400);
        assertThat(event.getValue().getErrorMessage()).isEqualTo("provider rejected the send");
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
