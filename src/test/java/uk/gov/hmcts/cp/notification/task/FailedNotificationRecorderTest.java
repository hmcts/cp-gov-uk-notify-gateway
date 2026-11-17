package uk.gov.hmcts.cp.notification.task;

import static jakarta.json.spi.JsonProvider.provider;
import static java.util.UUID.fromString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.STARTED;

import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class FailedNotificationRecorderTest {
    private static final UUID NOTIFICATION_ID = fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80");

    @Mock
    private NotificationStatusService statusService;

    @Test
    void should_mark_the_notification_failed_and_complete_the_task_without_a_retry() {
        final FailedNotificationRecorder failedNotificationRecorder =
                new FailedNotificationRecorder(statusService, new TaskRetrySchedule(new Clock()));
        final NotificationFailure notificationFailure =
                new NotificationFailure(NOTIFICATION_ID, EMAIL, 400, "bad request", PROVIDER_REJECTED);

        final ExecutionInfo result = failedNotificationRecorder.recordFailed(notificationFailure, aJobWithRetriesLeft());

        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        assertThat(result.isShouldRetry()).isFalse();
        verify(statusService).markFailed(notificationFailure);
    }

    private static ExecutionInfo aJobWithRetriesLeft() {
        final JsonObject jobData = provider().createObjectBuilder()
                .add("notificationId", NOTIFICATION_ID.toString())
                .build();
        return executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(SendEmailTask.TASK_NAME)
                .withExecutionStatus(STARTED)
                .withRetryAttemptsRemaining(3)
                .build();
    }
}
