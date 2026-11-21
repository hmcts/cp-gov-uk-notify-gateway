package uk.gov.hmcts.cp.notification.task;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.LogField.CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.LogField.DEPENDENCY;
import static uk.gov.hmcts.cp.notification.logging.LogField.OPERATION;
import static uk.gov.hmcts.cp.notification.logging.LogField.OUTCOME;
import static uk.gov.hmcts.cp.notification.logging.LogField.REASON;
import static uk.gov.hmcts.cp.notification.logging.LogField.STATUS_CODE;
import static uk.gov.hmcts.cp.notification.task.RetryCause.GOV_NOTIFY_SEND;
import static uk.gov.hmcts.cp.notification.task.RetryCause.STATUS_CHECK;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.logging.CallOutcome;
import uk.gov.hmcts.cp.notification.logging.Channel;
import uk.gov.hmcts.cp.notification.logging.Dependency;
import uk.gov.hmcts.cp.notification.logging.FailureReason;
import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;
import uk.gov.hmcts.cp.notification.logging.Operation;
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
 * What a task does when a call to Gov.Notify fails: sending an email or a letter, or asking for its status. A failure
 * that may recover is retried by {@link TransientFailurePolicy}. Any other is logged with the call's fields
 * (PEG-3720) and the notification recorded as FAILED. Without an HTTP response, the line and the outcome reason say
 * whether Gov.Notify could not be reached or did not respond, so the outage alert can count it.
 */
@Component
public class GovNotifyFailureHandler {
    private static final String EMAIL_SEND_EXHAUSTED_REASON = "Gov.Notify send did not recover within the retry window";
    private static final String LETTER_SEND_EXHAUSTED_REASON =
            "Gov.Notify letter send did not recover within the retry window";
    private static final String STATUS_CHECK_EXHAUSTED_REASON =
            "Gov.Notify status polling did not recover within the retry window";

    private final Logger logger = getLogger(GovNotifyFailureHandler.class);

    private final GovNotifyFailureClassifier govNotifyFailureClassifier;
    private final FailureReasonClassifier failureReasonClassifier;
    private final OutcomeReasonClassifier outcomeReasonClassifier;
    private final TransientFailurePolicy failurePolicy;
    private final FailedNotificationRecorder failedNotificationRecorder;

    public GovNotifyFailureHandler(
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

    public ExecutionInfo sendFailed(
            final UUID notificationId,
            final Channel channel,
            final GovNotifyException govNotifyException,
            final ExecutionInfo executionInfo,
            final List<Long> retryDurationsSecs) {
        final GovNotifyCall govNotifyCall =
                new GovNotifyCall(GOV_NOTIFY_SEND, notificationId, channel, sendExhaustedReasonFor(channel));
        return failed(govNotifyCall, govNotifyException, executionInfo, retryDurationsSecs);
    }

    public ExecutionInfo statusCheckFailed(
            final UUID notificationId,
            final Channel channel,
            final GovNotifyException govNotifyException,
            final ExecutionInfo executionInfo,
            final List<Long> retryDurationsSecs) {
        final GovNotifyCall govNotifyCall =
                new GovNotifyCall(STATUS_CHECK, notificationId, channel, STATUS_CHECK_EXHAUSTED_REASON);
        return failed(govNotifyCall, govNotifyException, executionInfo, retryDurationsSecs);
    }

    private ExecutionInfo failed(
            final GovNotifyCall govNotifyCall,
            final GovNotifyException govNotifyException,
            final ExecutionInfo executionInfo,
            final List<Long> retryDurationsSecs) {
        final Optional<Integer> httpStatusCode = govNotifyException.getHttpStatusCode();
        final ExecutionInfo result;
        if (govNotifyFailureClassifier.isTemporary(govNotifyException)) {
            final TransientFailure transientFailure = new TransientFailure(
                    govNotifyCall.notificationId(),
                    govNotifyCall.retryCause(),
                    govNotifyCall.channel(),
                    httpStatusCode,
                    govNotifyException,
                    govNotifyCall.exhaustedReason());
            result = failurePolicy.retryOrFail(transientFailure, executionInfo, retryDurationsSecs);
        } else {
            final Optional<FailureReason> failureReason =
                    failureReasonClassifier.classify(httpStatusCode, govNotifyException);
            logFailedPermanently(govNotifyCall, httpStatusCode, failureReason);
            final OutcomeReason outcomeReason = outcomeReasonClassifier.providerFailed(failureReason);
            final NotificationFailure notificationFailure = new NotificationFailure(
                    govNotifyCall.notificationId(),
                    govNotifyCall.channel(),
                    govNotifyException.getHttpStatus(),
                    govNotifyException.getMessage(),
                    outcomeReason);
            result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
        }
        return result;
    }

    /** Sent to the requesting team in {@code notification-failed}, so each keeps the text it has always had. */
    private String sendExhaustedReasonFor(final Channel channel) {
        final String exhaustedReason;
        if (channel == LETTER) {
            exhaustedReason = LETTER_SEND_EXHAUSTED_REASON;
        } else {
            exhaustedReason = EMAIL_SEND_EXHAUSTED_REASON;
        }
        return exhaustedReason;
    }

    private void logFailedPermanently(
            final GovNotifyCall govNotifyCall,
            final Optional<Integer> httpStatusCode,
            final Optional<FailureReason> failureReason) {
        final RetryCause retryCause = govNotifyCall.retryCause();
        final Operation operation = retryCause.getOperation();
        final Channel channel = govNotifyCall.channel();
        final String operationName = operation.getFieldValue();
        final String channelName = channel.getFieldValue();
        final Optional<String> httpStatusCodeText = httpStatusCode.map(String::valueOf);
        final String message = format(
                "Permanent Gov.Notify %s failure for %s notification '%s' (http '%s') — marking FAILED",
                operationName, channelName, govNotifyCall.notificationId(), httpStatusCodeText.orElse("none"));
        final LoggingEventBuilder loggingEventBuilder = logger.atWarn()
                .addKeyValue(DEPENDENCY, Dependency.GOV_UK_NOTIFY.getFieldValue())
                .addKeyValue(OPERATION, operationName)
                .addKeyValue(CHANNEL, channelName)
                .addKeyValue(OUTCOME, CallOutcome.PERMANENT_FAILURE.getFieldValue());
        httpStatusCode.ifPresent(code -> loggingEventBuilder.addKeyValue(STATUS_CODE, code));
        failureReason.ifPresent(reason -> loggingEventBuilder.addKeyValue(REASON, reason.getFieldValue()));
        loggingEventBuilder.log(message);
    }

    /** Which Gov.Notify call failed, for which notification, and what to record if its retries run out. */
    private record GovNotifyCall(RetryCause retryCause, UUID notificationId, Channel channel, String exhaustedReason) {
    }
}
