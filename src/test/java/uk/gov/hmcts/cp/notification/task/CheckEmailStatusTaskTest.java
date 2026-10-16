package uk.gov.hmcts.cp.notification.task;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.sender.GovNotifyClient;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.NotificationStatus;
import uk.gov.hmcts.cp.notification.service.NotificationEmailDetails;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckEmailStatusTaskTest {
    private static final List<Long> LEGACY_EMAIL_RETRY_DURATIONS =
            List.of(60L, 300L, 1800L, 3600L, 7200L, 14400L);

    @Mock
    private GovNotifyClient govNotifyClient;
    @Mock
    private NotificationStatusService statusService;

    private CheckEmailStatusTask task;

    @BeforeEach
    void setUp() {
        task = new CheckEmailStatusTask(govNotifyClient, statusService,
                new TransientFailurePolicy(statusService, new Clock(), LEGACY_EMAIL_RETRY_DURATIONS),
                LEGACY_EMAIL_RETRY_DURATIONS);
    }

    @Test
    void should_preserve_the_legacy_email_retry_durations() {
        assertThat(task.getRetryDurationsInSecs()).hasValue(LEGACY_EMAIL_RETRY_DURATIONS);
    }

    @Nested
    class WhenPollingSucceeds {

        @Test
        void should_mark_sent_with_the_captured_email_details_when_poll_returns_delivered() {
            final UUID id = UUID.randomUUID();
            final String reference = "notify-ref-123";
            when(govNotifyClient.checkStatus(reference)).thenReturn(NotificationStatus.DELIVERED);

            final ExecutionInfo result = task.execute(checkStatusJob(id, reference));

            verify(statusService).markSent(id, new NotificationEmailDetails(
                    "Your NCES extract", "Please find your report attached.", "noreply@justice.gov.uk"));
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_re_poll_when_delivery_is_still_in_progress() {
            final UUID id = UUID.randomUUID();
            final String reference = "notify-ref-123";
            when(govNotifyClient.checkStatus(reference)).thenReturn(NotificationStatus.SENDING);

            final ExecutionInfo result = task.execute(checkStatusJob(id, reference));

            verifyNoInteractions(statusService);
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
        }

        @Test
        void should_mark_failed_and_complete_when_poll_returns_a_terminal_failure_status() {
            final UUID id = UUID.randomUUID();
            final String reference = "notify-ref-123";
            when(govNotifyClient.checkStatus(reference)).thenReturn(NotificationStatus.PERMANENT_FAILURE);

            final ExecutionInfo result = task.execute(checkStatusJob(id, reference));

            verify(statusService).markFailed(eq(id), isNull(),
                    eq("Gov.Notify responded with status 'permanent-failure'"));
            verify(statusService, never()).markSent(any(), any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_mark_failed_and_complete_when_poll_returns_temporary_failure() {
            final UUID id = UUID.randomUUID();
            final String reference = "notify-ref-123";
            when(govNotifyClient.checkStatus(reference)).thenReturn(NotificationStatus.TEMPORARY_FAILURE);

            final ExecutionInfo result = task.execute(checkStatusJob(id, reference));

            verify(statusService).markFailed(eq(id), isNull(),
                    eq("Gov.Notify responded with status 'temporary-failure'"));
            verify(statusService, never()).markSent(any(), any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_mark_failed_and_complete_when_still_in_progress_but_status_check_retries_are_exhausted() {
            final UUID id = UUID.randomUUID();
            final String reference = "notify-ref-123";
            when(govNotifyClient.checkStatus(reference)).thenReturn(NotificationStatus.SENDING);

            final ExecutionInfo result = task.execute(checkStatusJobWithRetriesRemaining(id, reference, 0));

            verify(statusService).markFailed(eq(id), isNull(),
                    eq("Gov.Notify did not reach a terminal status within the retry window (last status 'sending')"));
            verify(statusService, never()).markSent(any(), any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
    }

    @Nested
    class WhenPollingFails {

        @Test
        void should_re_poll_when_the_status_poll_fails_transiently() {
            final UUID id = UUID.randomUUID();
            final String reference = "notify-ref-123";
            when(govNotifyClient.checkStatus(reference))
                    .thenThrow(new GovNotifyException(500, "Gov.Notify unavailable", null));

            final ExecutionInfo result = task.execute(checkStatusJob(id, reference));

            verifyNoInteractions(statusService);
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
        }

        @Test
        void should_mark_failed_and_complete_when_the_status_poll_keeps_failing_transiently_until_retries_are_exhausted() {
            final UUID id = UUID.randomUUID();
            final String reference = "notify-ref-123";
            when(govNotifyClient.checkStatus(reference))
                    .thenThrow(new GovNotifyException(500, "Gov.Notify unavailable", null));

            final ExecutionInfo result = task.execute(checkStatusJobWithRetriesRemaining(id, reference, 0));

            verify(statusService).markFailed(eq(id), eq(500),
                    eq("Gov.Notify status polling did not recover within the retry window"));
            verify(statusService, never()).markSent(any(), any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_mark_failed_and_complete_when_the_status_poll_fails_permanently() {
            final UUID id = UUID.randomUUID();
            final String reference = "notify-ref-123";
            when(govNotifyClient.checkStatus(reference))
                    .thenThrow(new GovNotifyException(400, "bad request", null));

            final ExecutionInfo result = task.execute(checkStatusJob(id, reference));

            verify(statusService).markFailed(id, 400, "bad request");
            verify(statusService, never()).markSent(any(), any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
    }

    private ExecutionInfo checkStatusJob(final UUID notificationId, final String reference) {
        return ExecutionInfo.executionInfo()
                .withJobData(jobData(notificationId, reference))
                .withAssignedTaskName(CheckEmailStatusTask.TASK_NAME)
                .withAssignedTaskStartTime(ZonedDateTime.now(ZoneOffset.UTC))
                .withExecutionStatus(ExecutionStatus.STARTED)
                .build();
    }

    private ExecutionInfo checkStatusJobWithRetriesRemaining(
            final UUID notificationId, final String reference, final int retriesRemaining) {
        return ExecutionInfo.executionInfo()
                .withJobData(jobData(notificationId, reference))
                .withAssignedTaskName(CheckEmailStatusTask.TASK_NAME)
                .withAssignedTaskStartTime(ZonedDateTime.now(ZoneOffset.UTC))
                .withExecutionStatus(ExecutionStatus.STARTED)
                .withRetryAttemptsRemaining(retriesRemaining)
                .build();
    }

    private static JsonObject jobData(final UUID notificationId, final String reference) {
        return Json.createObjectBuilder()
                .add(CheckEmailStatusTask.KEY_NOTIFICATION_ID, notificationId.toString())
                .add(CheckEmailStatusTask.KEY_REFERENCE, reference)
                .add(CheckEmailStatusTask.KEY_EMAIL_SUBJECT, "Your NCES extract")
                .add(CheckEmailStatusTask.KEY_EMAIL_BODY, "Please find your report attached.")
                .add(CheckEmailStatusTask.KEY_REPLY_TO_ADDRESS, "noreply@justice.gov.uk")
                .build();
    }
}
