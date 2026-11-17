package uk.gov.hmcts.cp.notification.task;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.hmcts.cp.notification.logging.LogField.CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.LogField.DEPENDENCY;
import static uk.gov.hmcts.cp.notification.logging.LogField.OPERATION;
import static uk.gov.hmcts.cp.notification.logging.LogField.OUTCOME;
import static uk.gov.hmcts.cp.notification.logging.LogField.REASON;
import static uk.gov.hmcts.cp.notification.logging.LogField.STATUS_CODE;
import static uk.gov.hmcts.cp.notification.task.RetryCause.STATUS_CHECK;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.logging.Channel;
import uk.gov.hmcts.cp.notification.logging.FailureReason;
import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;
import uk.gov.hmcts.cp.notification.logging.OutcomeReason;
import uk.gov.hmcts.cp.notification.logging.OutcomeReasonClassifier;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.GovNotifyFailureClassifier;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What an email or letter status check does when asking Gov.Notify for the status fails. A failure that may recover
 * is retried by {@link TransientFailurePolicy}. Any other is logged with the call's fields (PEG-3720) and the
 * notification recorded as FAILED, as a permanent send failure is.
 */
@Component
public class GovNotifyStatusCheckFailureHandler {
    private final Logger logger = getLogger(GovNotifyStatusCheckFailureHandler.class);

    private final GovNotifyFailureClassifier govNotifyFailureClassifier;
    private final FailureReasonClassifier failureReasonClassifier;
    private final OutcomeReasonClassifier outcomeReasonClassifier;
    private final TransientFailurePolicy failurePolicy;
    private final FailedNotificationRecorder failedNotificationRecorder;

    public GovNotifyStatusCheckFailureHandler(
            final GovNotifyFailureClassifier govNotifyFailureClassifier,
            final FailureReasonClassifier failureReasonClassifier,
            final OutcomeReasonClassifier outcomeReasonClassifier,
            final TransientFailurePolicy failurePolicy,
            final FailedNotificationRecorder failedNotificationRecorder) {
        this.govNotifyFailureClassifier = govNotifyFailureClassifier;
        this.failureReasonClassifier = failureReasonClassifier;
        this.outcomeReasonClassifier = outcomeReasonClassifier;
        this.failurePolicy = failurePolicy;
        this.failedNotificationRecorder = failedNotificationRecorder;
    }

    public ExecutionInfo statusCheckFailed(
            final UUID notificationId,
            final Channel channel,
            final GovNotifyException govNotifyException,
            final ExecutionInfo executionInfo,
            final List<Long> retryDurationsSecs) {
        final Optional<Integer> httpStatusCode = govNotifyException.getHttpStatusCode();
        final ExecutionInfo result;
        if (govNotifyFailureClassifier.isTemporary(govNotifyException)) {
            final TransientFailure transientFailure = new TransientFailure(
                    notificationId,
                    STATUS_CHECK,
                    channel,
                    httpStatusCode,
                    govNotifyException,
                    "Gov.Notify status polling did not recover within the retry window");
            result = failurePolicy.retryOrFail(transientFailure, executionInfo, retryDurationsSecs);
        } else {
            final Optional<FailureReason> failureReason =
                    failureReasonClassifier.classify(httpStatusCode, govNotifyException);
            logStatusCheckFailedPermanently(notificationId, channel, httpStatusCode, failureReason);
            final OutcomeReason outcomeReason = outcomeReasonClassifier.providerFailed(failureReason);
            final NotificationFailure notificationFailure = new NotificationFailure(
                    notificationId,
                    channel,
                    govNotifyException.getHttpStatus(),
                    govNotifyException.getMessage(),
                    outcomeReason);
            result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
        }
        return result;
    }

    /** {@code failureReason} says why when Gov.Notify gave no HTTP response. */
    private void logStatusCheckFailedPermanently(
            final UUID notificationId,
            final Channel channel,
            final Optional<Integer> httpStatusCode,
            final Optional<FailureReason> failureReason) {
        final Optional<String> httpStatusCodeText = httpStatusCode.map(String::valueOf);
        final String message = format(
                "Permanent Gov.Notify status check failure for notification '%s' (http '%s') — marking FAILED",
                notificationId, httpStatusCodeText.orElse("none"));
        final String channelName = channel.getFieldValue();
        final LoggingEventBuilder loggingEventBuilder = logger.atWarn()
                .addKeyValue(DEPENDENCY, "gov-uk-notify")
                .addKeyValue(OPERATION, "status-check")
                .addKeyValue(CHANNEL, channelName)
                .addKeyValue(OUTCOME, "permanent-failure");
        httpStatusCode.ifPresent(code -> loggingEventBuilder.addKeyValue(STATUS_CODE, code));
        failureReason.ifPresent(reason -> loggingEventBuilder.addKeyValue(REASON, reason.getFieldValue()));
        loggingEventBuilder.log(message);
    }
}
