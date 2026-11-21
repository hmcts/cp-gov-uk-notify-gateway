package uk.gov.hmcts.cp.notification.task;

import static java.lang.String.format;
import static java.util.List.copyOf;
import static java.util.Optional.of;
import static java.util.UUID.fromString;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.DELIVERY_FAILED;
import static uk.gov.hmcts.cp.notification.sender.NotificationStatus.DELIVERED;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.json.JsonObject;

import uk.gov.hmcts.cp.notification.sender.GovNotifyClient;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.NotificationStatus;
import uk.gov.hmcts.cp.notification.service.NotificationEmailDetails;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Task(CheckEmailStatusTask.TASK_NAME)
@Component
public class CheckEmailStatusTask implements ExecutableTask {
    public static final String TASK_NAME = "CHECK_EMAIL_STATUS";
    public static final String KEY_NOTIFICATION_ID = "notificationId";
    public static final String KEY_REFERENCE = "reference";
    public static final String KEY_EMAIL_SUBJECT = "emailSubject";
    public static final String KEY_EMAIL_BODY = "emailBody";
    public static final String KEY_REPLY_TO_ADDRESS = "replyToAddress";

    private final GovNotifyClient govNotifyClient;
    private final NotificationStatusService statusService;
    private final TransientFailurePolicy failurePolicy;
    private final FailedNotificationRecorder failedNotificationRecorder;
    private final GovNotifyFailureHandler govNotifyFailureHandler;
    private final TaskRetrySchedule taskRetrySchedule;
    private final List<Long> retryDurationsSecs;

    public CheckEmailStatusTask(
            final GovNotifyClient govNotifyClient,
            final NotificationStatusService statusService,
            final TransientFailurePolicy failurePolicy,
            final FailedNotificationRecorder failedNotificationRecorder,
            final GovNotifyFailureHandler govNotifyFailureHandler,
            final TaskRetrySchedule taskRetrySchedule,
            @Value("${cp.notification.retry.email-durations-secs}") final List<Long> retryDurationsSecs) {
        this.govNotifyClient = govNotifyClient;
        this.statusService = statusService;
        this.failurePolicy = failurePolicy;
        this.failedNotificationRecorder = failedNotificationRecorder;
        this.govNotifyFailureHandler = govNotifyFailureHandler;
        this.taskRetrySchedule = taskRetrySchedule;
        this.retryDurationsSecs = copyOf(retryDurationsSecs);
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final UUID notificationId =
                fromString(executionInfo.getJobData().getString(KEY_NOTIFICATION_ID));
        final String reference = executionInfo.getJobData().getString(KEY_REFERENCE);
        ExecutionInfo result;
        try {
            result = evaluate(govNotifyClient.checkStatus(reference), notificationId, executionInfo);
        } catch (final GovNotifyException govNotifyException) {
            result = govNotifyFailureHandler.statusCheckFailed(
                    notificationId, EMAIL, govNotifyException, executionInfo, retryDurationsSecs);
        }
        return result;
    }

    private ExecutionInfo evaluate(
            final NotificationStatus status, final UUID notificationId, final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        if (status == DELIVERED) {
            statusService.markSent(notificationId, emailDetailsFrom(executionInfo.getJobData()));
            result = taskRetrySchedule.completed(executionInfo);
        } else if (status.isInProgress()) {
            final String exhaustedReason = format(
                    "Gov.Notify did not reach a terminal status within the retry window (last status '%s')",
                    status.getStatus());
            result = failurePolicy.pollAgainOrFail(
                    notificationId, EMAIL, exhaustedReason, executionInfo, retryDurationsSecs);
        } else {
            final String failedReason = format("Gov.Notify responded with status '%s'", status.getStatus());
            final NotificationFailure notificationFailure =
                    new NotificationFailure(notificationId, EMAIL, null, failedReason, DELIVERY_FAILED);
            result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
        }
        return result;
    }

    private static NotificationEmailDetails emailDetailsFrom(final JsonObject jobData) {
        return new NotificationEmailDetails(
                jobData.getString(KEY_EMAIL_SUBJECT, null),
                jobData.getString(KEY_EMAIL_BODY, null),
                jobData.getString(KEY_REPLY_TO_ADDRESS, null));
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return of(retryDurationsSecs);
    }
}
