package uk.gov.hmcts.cp.notification.task;

import static uk.gov.hmcts.cp.notification.sender.GovNotifyFailureClassifier.isTemporary;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.json.JsonObject;

import uk.gov.hmcts.cp.notification.sender.GovNotifyClient;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.NotificationStatus;
import uk.gov.hmcts.cp.notification.service.NotificationEmailDetails;
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
    private final List<Long> retryDurationsSecs;

    public CheckEmailStatusTask(
            final GovNotifyClient govNotifyClient,
            final NotificationStatusService statusService,
            final TransientFailurePolicy failurePolicy,
            @Value("${cp.notification.retry.email-durations-secs}") final List<Long> retryDurationsSecs) {
        this.govNotifyClient = govNotifyClient;
        this.statusService = statusService;
        this.failurePolicy = failurePolicy;
        this.retryDurationsSecs = retryDurationsSecs;
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final UUID notificationId =
                UUID.fromString(executionInfo.getJobData().getString(KEY_NOTIFICATION_ID));
        final String reference = executionInfo.getJobData().getString(KEY_REFERENCE);
        ExecutionInfo result;
        try {
            result = evaluate(govNotifyClient.checkStatus(reference), notificationId, executionInfo);
        } catch (final GovNotifyException e) {
            result = handlePollFailure(notificationId, executionInfo, e);
        }
        return result;
    }

    private ExecutionInfo evaluate(
            final NotificationStatus status, final UUID notificationId, final ExecutionInfo executionInfo) {
        final ExecutionInfo result;
        if (status == NotificationStatus.DELIVERED) {
            statusService.markSent(notificationId, emailDetailsFrom(executionInfo.getJobData()));
            result = TransientFailurePolicy.completed(executionInfo);
        } else if (status.isInProgress()) {
            result = failurePolicy.retryOrFail(notificationId, null,
                    "Gov.Notify did not reach a terminal status within the retry window (last status '"
                            + status.getStatus() + "')", executionInfo, RetryCause.STILL_IN_PROGRESS);
        } else {
            result = failurePolicy.fail(notificationId, null,
                    "Gov.Notify responded with status '" + status.getStatus() + "'", executionInfo);
        }
        return result;
    }

    private ExecutionInfo handlePollFailure(
            final UUID notificationId, final ExecutionInfo executionInfo, final GovNotifyException e) {
        final ExecutionInfo result;
        if (isTemporary(e.getHttpStatus(), e.getMessage())) {
            result = failurePolicy.retryOrFail(notificationId, e.getHttpStatus(),
                    "Gov.Notify status polling did not recover within the retry window", executionInfo,
                    RetryCause.STATUS_CHECK);
        } else {
            result = failurePolicy.fail(notificationId, e.getHttpStatus(), e.getMessage(), executionInfo);
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
        return Optional.of(retryDurationsSecs);
    }
}
