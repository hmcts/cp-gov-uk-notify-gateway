package uk.gov.hmcts.cp.notification.integration.testdata;

import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;
import uk.gov.hmcts.cp.notification.logging.OutcomeReasonClassifier;
import uk.gov.hmcts.cp.notification.sender.GovNotifyFailureClassifier;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.task.FailedNotificationRecorder;
import uk.gov.hmcts.cp.notification.task.GovNotifyStatusCheckFailureHandler;
import uk.gov.hmcts.cp.notification.task.TaskRetrySchedule;
import uk.gov.hmcts.cp.notification.task.TransientFailurePolicy;

/**
 * The real failure handling a notification task uses, for task tests that check what a failure leads to and what it
 * logs.
 */
public final class TransientFailurePolicyFactory {

    private TransientFailurePolicyFactory() {
    }

    public static FailedNotificationRecorder aFailedNotificationRecorder(
            final NotificationStatusService statusService,
            final TaskRetrySchedule taskRetrySchedule) {
        return new FailedNotificationRecorder(statusService, taskRetrySchedule);
    }

    public static TransientFailurePolicy aTransientFailurePolicy(
            final NotificationStatusService statusService,
            final TaskRetrySchedule taskRetrySchedule) {
        final FailedNotificationRecorder failedNotificationRecorder =
                aFailedNotificationRecorder(statusService, taskRetrySchedule);
        return new TransientFailurePolicy(
                taskRetrySchedule,
                failedNotificationRecorder,
                new FailureReasonClassifier(),
                new OutcomeReasonClassifier());
    }

    public static GovNotifyStatusCheckFailureHandler aStatusCheckFailureHandler(
            final NotificationStatusService statusService,
            final TaskRetrySchedule taskRetrySchedule) {
        final TransientFailurePolicy transientFailurePolicy = aTransientFailurePolicy(statusService, taskRetrySchedule);
        final FailedNotificationRecorder failedNotificationRecorder =
                aFailedNotificationRecorder(statusService, taskRetrySchedule);
        return new GovNotifyStatusCheckFailureHandler(
                new GovNotifyFailureClassifier(),
                new FailureReasonClassifier(),
                new OutcomeReasonClassifier(),
                transientFailurePolicy,
                failedNotificationRecorder);
    }
}
