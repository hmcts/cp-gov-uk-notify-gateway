package uk.gov.hmcts.cp.notification.task;

import jakarta.json.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.service.NotificationEmailDetails;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RecordSentTaskTest {
    private static final List<Long> LEGACY_EMAIL_RETRY_DURATIONS =
            List.of(60L, 300L, 1800L, 3600L, 7200L, 14400L);

    @Mock
    private NotificationStatusService statusService;

    private RecordSentTask task;

    private final UUID notificationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        task = new RecordSentTask(statusService, LEGACY_EMAIL_RETRY_DURATIONS);
    }

    @Test
    void should_record_sent_with_the_email_details_in_its_own_transaction_and_complete() {
        final ExecutionInfo result = task.execute(recordSentJob(6));

        verify(statusService).markSentInNewTransaction(notificationId, new NotificationEmailDetails(
                "Your document is ready", "Please find the document attached.", "NOREPLY@noreply.com"));
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 2, 1})
    void should_retry_without_marking_failed_when_recording_sent_fails_including_on_the_last_attempt(
            final int retriesRemaining) {
        doThrow(new IllegalStateException("database unavailable"))
                .when(statusService).markSentInNewTransaction(eq(notificationId), any());

        final ExecutionInfo result = task.execute(recordSentJob(retriesRemaining));

        verify(statusService, never()).markFailed(any(), any(), any());
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void should_use_the_legacy_email_retry_durations() {
        assertThat(task.getRetryDurationsInSecs()).hasValue(LEGACY_EMAIL_RETRY_DURATIONS);
    }

    private ExecutionInfo recordSentJob(final int retriesRemaining) {
        return ExecutionInfo.executionInfo()
                .withJobData(Json.createObjectBuilder()
                        .add(RecordSentTask.KEY_NOTIFICATION_ID, notificationId.toString())
                        .add(RecordSentTask.KEY_EMAIL_SUBJECT, "Your document is ready")
                        .add(RecordSentTask.KEY_EMAIL_BODY, "Please find the document attached.")
                        .add(RecordSentTask.KEY_REPLY_TO_ADDRESS, "NOREPLY@noreply.com")
                        .build())
                .withAssignedTaskName(RecordSentTask.TASK_NAME)
                .withAssignedTaskStartTime(ZonedDateTime.now(ZoneOffset.UTC))
                .withExecutionStatus(ExecutionStatus.STARTED)
                .withRetryAttemptsRemaining(retriesRemaining)
                .build();
    }
}
