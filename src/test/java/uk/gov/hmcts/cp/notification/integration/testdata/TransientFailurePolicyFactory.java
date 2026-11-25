package uk.gov.hmcts.cp.notification.integration.testdata;

import uk.gov.hmcts.cp.notification.failure.DownloadStatusTable;
import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;
import uk.gov.hmcts.cp.notification.logging.OutcomeReasonClassifier;
import uk.gov.hmcts.cp.notification.sender.GovNotifyFailureClassifier;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.task.AttachmentDownloadFailureHandler;
import uk.gov.hmcts.cp.notification.task.FailedNotificationRecorder;
import uk.gov.hmcts.cp.notification.task.GovNotifyFailureHandler;
import uk.gov.hmcts.cp.notification.task.Office365SendFailureHandler;
import uk.gov.hmcts.cp.notification.task.TaskRetrySchedule;
import uk.gov.hmcts.cp.notification.task.TransientFailurePolicy;

/**
 * The real failure handling a notification task uses, for task tests that check what a failure leads to and what it
 * logs.
 */
public final class TransientFailurePolicyFactory {

    private TransientFailurePolicyFactory() {
    }

    public static OutcomeReasonClassifier anOutcomeReasonClassifier() {
        return new OutcomeReasonClassifier(new DownloadStatusTable());
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
                anOutcomeReasonClassifier());
    }

    public static GovNotifyFailureHandler aGovNotifyFailureHandler(
            final NotificationStatusService statusService,
            final TaskRetrySchedule taskRetrySchedule) {
        final TransientFailurePolicy transientFailurePolicy = aTransientFailurePolicy(statusService, taskRetrySchedule);
        final FailedNotificationRecorder failedNotificationRecorder =
                aFailedNotificationRecorder(statusService, taskRetrySchedule);
        return new GovNotifyFailureHandler(
                new GovNotifyFailureClassifier(),
                new FailureReasonClassifier(),
                anOutcomeReasonClassifier(),
                transientFailurePolicy,
                failedNotificationRecorder);
    }

    public static AttachmentDownloadFailureHandler anAttachmentDownloadFailureHandler(
            final NotificationStatusService statusService,
            final TaskRetrySchedule taskRetrySchedule) {
        final TransientFailurePolicy transientFailurePolicy = aTransientFailurePolicy(statusService, taskRetrySchedule);
        final FailedNotificationRecorder failedNotificationRecorder =
                aFailedNotificationRecorder(statusService, taskRetrySchedule);
        return new AttachmentDownloadFailureHandler(
                transientFailurePolicy, failedNotificationRecorder, anOutcomeReasonClassifier());
    }

    public static Office365SendFailureHandler anOffice365SendFailureHandler(
            final NotificationStatusService statusService,
            final TaskRetrySchedule taskRetrySchedule) {
        final TransientFailurePolicy transientFailurePolicy = aTransientFailurePolicy(statusService, taskRetrySchedule);
        final FailedNotificationRecorder failedNotificationRecorder =
                aFailedNotificationRecorder(statusService, taskRetrySchedule);
        return new Office365SendFailureHandler(transientFailurePolicy, failedNotificationRecorder);
    }
}
