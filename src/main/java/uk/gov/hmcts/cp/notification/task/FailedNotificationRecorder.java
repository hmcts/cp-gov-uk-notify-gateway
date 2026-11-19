package uk.gov.hmcts.cp.notification.task;

import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

/**
 * Ends a notification task whose notification has failed: records it as FAILED, with the reason the requesting team
 * is alerted with, and finishes the task with no retry. A task that let the exception escape instead would be retried
 * by cp-task-manager and then quietly released, leaving the notification in its pre-send state for good.
 */
@Component
public class FailedNotificationRecorder {
    private final NotificationStatusService statusService;
    private final TaskRetrySchedule taskRetrySchedule;

    public FailedNotificationRecorder(
            final NotificationStatusService statusService, final TaskRetrySchedule taskRetrySchedule) {
        this.statusService = statusService;
        this.taskRetrySchedule = taskRetrySchedule;
    }

    public ExecutionInfo recordFailed(final NotificationFailure notificationFailure, final ExecutionInfo executionInfo) {
        statusService.markFailed(notificationFailure);
        return taskRetrySchedule.completed(executionInfo);
    }
}
