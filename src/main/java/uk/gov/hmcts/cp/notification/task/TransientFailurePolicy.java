package uk.gov.hmcts.cp.notification.task;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.hmcts.cp.notification.logging.LogField.CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.LogField.DEPENDENCY;
import static uk.gov.hmcts.cp.notification.logging.LogField.OPERATION;
import static uk.gov.hmcts.cp.notification.logging.LogField.OUTCOME;
import static uk.gov.hmcts.cp.notification.logging.LogField.REASON;
import static uk.gov.hmcts.cp.notification.logging.LogField.STATUS_CODE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.STATUS_UNKNOWN;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.logging.Channel;
import uk.gov.hmcts.cp.notification.logging.Dependency;
import uk.gov.hmcts.cp.notification.logging.FailureReason;
import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;
import uk.gov.hmcts.cp.notification.logging.Operation;
import uk.gov.hmcts.cp.notification.logging.OutcomeReason;
import uk.gov.hmcts.cp.notification.logging.OutcomeReasonClassifier;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a notification task does about something that may still come right: a call that failed in a way that may
 * recover, or Gov.Notify not having finished with the notification. Either is tried again until the task's retries
 * run out, then recorded as FAILED by {@link FailedNotificationRecorder}.
 *
 * <p>Each retry decision about a failed call is logged with the fields the provider outage alert counts (PEG-3720).
 */
@Component
public class TransientFailurePolicy {
    private final Logger logger = getLogger(TransientFailurePolicy.class);

    private final TaskRetrySchedule taskRetrySchedule;
    private final FailedNotificationRecorder failedNotificationRecorder;
    private final FailureReasonClassifier failureReasonClassifier;
    private final OutcomeReasonClassifier outcomeReasonClassifier;

    public TransientFailurePolicy(
            final TaskRetrySchedule taskRetrySchedule,
            final FailedNotificationRecorder failedNotificationRecorder,
            final FailureReasonClassifier failureReasonClassifier,
            final OutcomeReasonClassifier outcomeReasonClassifier) {
        this.taskRetrySchedule = taskRetrySchedule;
        this.failedNotificationRecorder = failedNotificationRecorder;
        this.failureReasonClassifier = failureReasonClassifier;
        this.outcomeReasonClassifier = outcomeReasonClassifier;
    }

    public ExecutionInfo retryOrFail(
            final TransientFailure transientFailure,
            final ExecutionInfo executionInfo,
            final List<Long> retryDurationsSecs) {
        final UUID notificationId = transientFailure.notificationId();
        final RetryCause retryCause = transientFailure.retryCause();
        final String callDescription = retryCause.getDescription();
        final Optional<Integer> httpStatusCode = transientFailure.httpStatusCode();
        final Throwable failure = transientFailure.failure();
        final Optional<FailureReason> failureReason = failureReasonClassifier.classify(httpStatusCode, failure);
        final String failureDetail = failureDetailOf(httpStatusCode, failureReason);
        final RetryStep retryStep = taskRetrySchedule.nextStep(executionInfo);
        return switch (retryStep) {
            case EXHAUSTED -> {
                final String message = format("""
                        Transient failure '%s' for notificationId '%s' (%s) \
                        did not recover after exhausting retries — marking FAILED""",
                        callDescription, notificationId, failureDetail);
                logTransientFailure(logger.atWarn(), transientFailure, failureReason, message);
                final Operation operation = retryCause.getOperation();
                final OutcomeReason outcomeReason = outcomeReasonClassifier.retriesExhausted(operation, failureReason);
                final NotificationFailure notificationFailure = new NotificationFailure(
                        notificationId,
                        transientFailure.channel(),
                        httpStatusCode.orElse(null),
                        transientFailure.exhaustedReason(),
                        outcomeReason);
                yield failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
            }
            case FINAL_ATTEMPT -> {
                final String message = format(
                        "Transient failure '%s' for notificationId '%s' (%s) — scheduling the final attempt",
                        callDescription, notificationId, failureDetail);
                logTransientFailure(logger.atInfo(), transientFailure, failureReason, message);
                yield taskRetrySchedule.finalAttempt(executionInfo, retryDurationsSecs);
            }
            case RETRY -> {
                final String message = format("Transient failure '%s' for notificationId '%s' (%s) — will retry",
                        callDescription, notificationId, failureDetail);
                logTransientFailure(logger.atInfo(), transientFailure, failureReason, message);
                yield taskRetrySchedule.retry(executionInfo);
            }
        };
    }

    /** Gov.Notify has not finished with the notification, which is not a failure until the task's retries run out. */
    public ExecutionInfo pollAgainOrFail(
            final UUID notificationId,
            final Channel channel,
            final String exhaustedReason,
            final ExecutionInfo executionInfo,
            final List<Long> retryDurationsSecs) {
        final RetryStep retryStep = taskRetrySchedule.nextStep(executionInfo);
        return switch (retryStep) {
            case EXHAUSTED -> {
                final String message = format(
                        "Notification '%s' still in progress at Gov.Notify after exhausting status polls — marking FAILED",
                        notificationId);
                logger.warn(message);
                final NotificationFailure notificationFailure =
                        new NotificationFailure(notificationId, channel, null, exhaustedReason, STATUS_UNKNOWN);
                yield failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
            }
            case FINAL_ATTEMPT -> {
                final String message = format(
                        "Notification '%s' still in progress at Gov.Notify — scheduling the final status poll",
                        notificationId);
                logger.info(message);
                yield taskRetrySchedule.finalAttempt(executionInfo, retryDurationsSecs);
            }
            case RETRY -> {
                final String message = format(
                        "Notification '%s' still in progress at Gov.Notify — polling again", notificationId);
                logger.info(message);
                yield taskRetrySchedule.retry(executionInfo);
            }
        };
    }

    /** The call that failed, its channel, and its status code or the reason there was none. */
    private void logTransientFailure(
            final LoggingEventBuilder loggingEventBuilder,
            final TransientFailure transientFailure,
            final Optional<FailureReason> failureReason,
            final String message) {
        final RetryCause retryCause = transientFailure.retryCause();
        final Dependency dependency = retryCause.getDependency();
        final Operation operation = retryCause.getOperation();
        final Channel channel = transientFailure.channel();
        final String dependencyName = dependency.getFieldValue();
        final String operationName = operation.getFieldValue();
        final String channelName = channel.getFieldValue();
        final Optional<Integer> httpStatusCode = transientFailure.httpStatusCode();
        final LoggingEventBuilder loggingEventBuilderWithFields = loggingEventBuilder
                .addKeyValue(DEPENDENCY, dependencyName)
                .addKeyValue(OPERATION, operationName)
                .addKeyValue(CHANNEL, channelName)
                .addKeyValue(OUTCOME, "transient-failure");
        httpStatusCode.ifPresent(code -> loggingEventBuilderWithFields.addKeyValue(STATUS_CODE, code));
        failureReason.ifPresent(reason -> loggingEventBuilderWithFields.addKeyValue(REASON, reason.getFieldValue()));
        loggingEventBuilderWithFields.log(message);
    }

    private String failureDetailOf(final Optional<Integer> httpStatusCode, final Optional<FailureReason> failureReason) {
        final String failureDetail;
        if (httpStatusCode.isPresent()) {
            failureDetail = format("statusCode '%s'", httpStatusCode.get());
        } else if (failureReason.isPresent()) {
            final FailureReason failureReasonValue = failureReason.get();
            failureDetail = format("the service %s", failureReasonValue.getDescription());
        } else {
            failureDetail = "no status code";
        }
        return failureDetail;
    }
}
