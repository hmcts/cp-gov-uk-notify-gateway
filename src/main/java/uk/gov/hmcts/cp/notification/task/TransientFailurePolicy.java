package uk.gov.hmcts.cp.notification.task;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.TRANSIENT_FAILURE;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.INPROGRESS;

import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObject;
import jakarta.json.spi.JsonProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.logging.Channel;
import uk.gov.hmcts.cp.notification.logging.Dependency;
import uk.gov.hmcts.cp.notification.logging.DependencyCall;
import uk.gov.hmcts.cp.notification.logging.DependencyCallLogger;
import uk.gov.hmcts.cp.notification.logging.FailureReason;
import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;
import uk.gov.hmcts.cp.notification.logging.Operation;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared retry/failure scaffolding for notification tasks.
 *
 * <p>Every notification task classifies a failure as either <em>transient</em> (retry until the
 * task-manager retry window is exhausted, then record a FAILED status) or <em>permanent</em>
 * (record FAILED immediately, no retry). Centralising the decision here guarantees the two
 * behaviours are identical across tasks and — crucially — that an exhausted transient failure is
 * never dropped silently: it always terminates in {@link NotificationStatusService#markFailed}.
 *
 * <p>Without this, an unhandled exception escaping a task's {@code execute} is caught by the
 * task-manager's generic catch-all, retried, and then quietly released once retries run out —
 * leaving the notification stuck in its pre-send state with no FAILED status recorded.
 *
 * <p>A transient failure with no status code is logged with its {@link FailureReason} (the dependency could not
 * be reached, or did not respond), and the reason is added to the FAILED reason once retries run out (PEG-3720).
 * Polling Gov.Notify for a notification still in progress is not a failure, so it has its own
 * {@link #pollAgainOrFail} with the same retry schedule.
 */
@Component
public class TransientFailurePolicy {
    private static final Logger LOG = LoggerFactory.getLogger(TransientFailurePolicy.class);

    public static final String KEY_FINAL_ATTEMPT = "finalAttempt";

    // Created once: Json.createObjectBuilder() looks up a new JSON provider on every call.
    private static final JsonBuilderFactory JSON_BUILDER_FACTORY = JsonProvider.provider().createBuilderFactory(null);

    // The Gov.Notify client reports "no HTTP response" as status 0.
    private static final int NO_HTTP_RESPONSE = 0;

    private final NotificationStatusService statusService;
    private final Clock clock;
    private final DependencyCallLogger dependencyCallLogger;
    private final FailureReasonClassifier failureReasonClassifier;
    private final List<Long> retryDurationsSecs;

    public TransientFailurePolicy(
            final NotificationStatusService statusService,
            final Clock clock,
            final DependencyCallLogger dependencyCallLogger,
            final FailureReasonClassifier failureReasonClassifier,
            @Value("${cp.notification.retry.email-durations-secs}") final List<Long> retryDurationsSecs) {
        this.statusService = statusService;
        this.clock = clock;
        this.dependencyCallLogger = dependencyCallLogger;
        this.failureReasonClassifier = failureReasonClassifier;
        this.retryDurationsSecs = List.copyOf(retryDurationsSecs);
    }

    /**
     * Handle a transient failure: retry while the task-manager retry window still has attempts
     * remaining, otherwise mark the notification FAILED with {@code exhaustedReason} and complete.
     *
     * @param notificationId  the notification being processed
     * @param statusCode      the HTTP/status code of the failure, or {@code null} if none applies; {@code 0} (the
     *                        Gov.Notify client's "no HTTP response") is logged as no status code
     * @param exhaustedReason the failure reason recorded once retries are exhausted; the {@link FailureReason}'s
     *                        description is added when there is one
     * @param executionInfo   the current execution
     * @param retryCause      the call that failed
     * @param channel         whether the notification is an email or a letter, for the failure's log fields
     * @param failure         the failure, from which the {@link FailureReason} is worked out
     * @return {@code INPROGRESS} + retry while more than one attempt remains, {@code INPROGRESS} rescheduled
     *         as the final attempt when one remains, otherwise {@code COMPLETED}
     */
    public ExecutionInfo retryOrFail(
            final UUID notificationId,
            final Integer statusCode,
            final String exhaustedReason,
            final ExecutionInfo executionInfo,
            final RetryCause retryCause,
            final Channel channel,
            final Throwable failure) {
        return retryOrFail(notificationId, statusCode, exhaustedReason, executionInfo, retryDurationsSecs, retryCause,
                channel, failure);
    }

    /**
     * {@link #retryOrFail(UUID, Integer, String, ExecutionInfo, RetryCause, Channel, Throwable)} for a task with its
     * own retry durations (the letter tasks): the final attempt is scheduled after the last of
     * {@code taskRetryDurationsSecs}.
     */
    public ExecutionInfo retryOrFail(
            final UUID notificationId,
            final Integer statusCode,
            final String exhaustedReason,
            final ExecutionInfo executionInfo,
            final List<Long> taskRetryDurationsSecs,
            final RetryCause retryCause,
            final Channel channel,
            final Throwable failure) {
        final Dependency dependency = retryCause.getDependency();
        final Operation operation = retryCause.getOperation();
        final String retryCauseDescription = retryCause.getDescription();
        final Optional<Integer> possibleStatusCode = ofNullable(statusCode);
        final Optional<Integer> httpStatusCode = possibleStatusCode.filter(this::isHttpStatus);
        // A status code says why on its own; without one, the failure's causes say whether the dependency could not
        // be reached or did not respond.
        Optional<FailureReason> failureReason = Optional.empty();
        if (httpStatusCode.isEmpty()) {
            failureReason = failureReasonClassifier.classify(failure);
        }
        final DependencyCall dependencyCall = new DependencyCall(
                dependency, operation, channel, TRANSIENT_FAILURE, httpStatusCode, failureReason);
        final String failureDetail = failureDetailOf(dependencyCall);
        final ExecutionInfo result;
        if (retriesExhausted(executionInfo)) {
            final String message = format("Transient failure '%s' for notificationId '%s' (%s) did not recover"
                    + " after exhausting retries — marking FAILED", retryCauseDescription, notificationId, failureDetail);
            dependencyCallLogger.warn(dependencyCall, message);
            final String failedReason = failedReasonOf(exhaustedReason, dependencyCall);
            result = fail(notificationId, statusCode, failedReason, executionInfo);
        } else if (isLastRetry(executionInfo)) {
            final String message = format("Transient failure '%s' for notificationId '%s' (%s) — scheduling the final"
                    + " attempt", retryCauseDescription, notificationId, failureDetail);
            dependencyCallLogger.info(dependencyCall, message);
            result = finalAttempt(executionInfo, taskRetryDurationsSecs.getLast());
        } else {
            final String message = format("Transient failure '%s' for notificationId '%s' (%s) — will retry",
                    retryCauseDescription, notificationId, failureDetail);
            dependencyCallLogger.info(dependencyCall, message);
            result = retry(executionInfo);
        }
        return result;
    }

    /**
     * Gov.Notify still reports the notification in progress (not a failure): poll again while the task-manager
     * retry window still has attempts remaining, otherwise mark it FAILED with {@code exhaustedReason} and complete.
     */
    public ExecutionInfo pollAgainOrFail(
            final UUID notificationId, final String exhaustedReason, final ExecutionInfo executionInfo) {
        return pollAgainOrFail(notificationId, exhaustedReason, executionInfo, retryDurationsSecs);
    }

    /**
     * {@link #pollAgainOrFail(UUID, String, ExecutionInfo)} for a task with its own retry durations (the letter
     * tasks): the final poll is scheduled after the last of {@code taskRetryDurationsSecs}.
     */
    public ExecutionInfo pollAgainOrFail(
            final UUID notificationId,
            final String exhaustedReason,
            final ExecutionInfo executionInfo,
            final List<Long> taskRetryDurationsSecs) {
        final ExecutionInfo result;
        if (retriesExhausted(executionInfo)) {
            LOG.warn("Notification {} still in progress at Gov.Notify after exhausting status polls"
                    + " — marking FAILED", notificationId);
            result = fail(notificationId, null, exhaustedReason, executionInfo);
        } else if (isLastRetry(executionInfo)) {
            LOG.info("Notification {} still in progress at Gov.Notify — scheduling the final status poll",
                    notificationId);
            result = finalAttempt(executionInfo, taskRetryDurationsSecs.getLast());
        } else {
            LOG.info("Notification {} still in progress at Gov.Notify — polling again", notificationId);
            result = retry(executionInfo);
        }
        return result;
    }

    /**
     * Handle a permanent failure: record FAILED immediately and complete, with no retry.
     */
    public ExecutionInfo fail(
            final UUID notificationId,
            final Integer statusCode,
            final String reason,
            final ExecutionInfo executionInfo) {
        statusService.markFailed(notificationId, statusCode, reason);
        return completed(executionInfo);
    }

    public static ExecutionInfo completed(final ExecutionInfo executionInfo) {
        return executionInfo().from(executionInfo).withExecutionStatus(COMPLETED).build();
    }

    public static ExecutionInfo retry(final ExecutionInfo executionInfo) {
        return executionInfo().from(executionInfo)
                .withExecutionStatus(INPROGRESS)
                .withShouldRetry(true)
                .build();
    }

    private boolean isHttpStatus(final Integer statusCode) {
        return statusCode != NO_HTTP_RESPONSE;
    }

    private String failureDetailOf(final DependencyCall dependencyCall) {
        final Optional<Integer> httpStatusCode = dependencyCall.statusCode();
        final Optional<FailureReason> failureReason = dependencyCall.failureReason();
        final String failureDetail;
        if (httpStatusCode.isPresent()) {
            final Integer httpStatusCodeValue = httpStatusCode.get();
            failureDetail = format("statusCode '%s'", httpStatusCodeValue);
        } else if (failureReason.isPresent()) {
            final FailureReason failureReasonValue = failureReason.get();
            final String reasonDescription = failureReasonValue.getDescription();
            failureDetail = format("the service %s", reasonDescription);
        } else {
            failureDetail = "no status code";
        }
        return failureDetail;
    }

    private String failedReasonOf(final String exhaustedReason, final DependencyCall dependencyCall) {
        final Optional<FailureReason> failureReason = dependencyCall.failureReason();
        String failedReason = exhaustedReason;
        if (failureReason.isPresent()) {
            final FailureReason failureReasonValue = failureReason.get();
            final String reasonDescription = failureReasonValue.getDescription();
            failedReason = format("%s: the service %s", exhaustedReason, reasonDescription);
        }
        return failedReason;
    }

    // Workaround for cp-task-manager, which never picks up a job with no retry attempts remaining: the run
    // that would see retries exhausted never happens, so the notification stays QUEUED for good. Instead the
    // last retry reschedules the same task (INPROGRESS, no retry), which keeps its remaining count, and flags
    // it as the final attempt so a failure there marks it FAILED.
    private ExecutionInfo finalAttempt(final ExecutionInfo executionInfo, final long delaySecs) {
        final JsonObject jobData = JSON_BUILDER_FACTORY.createObjectBuilder(executionInfo.getJobData())
                .add(KEY_FINAL_ATTEMPT, true)
                .build();
        return executionInfo().from(executionInfo)
                .withJobData(jobData)
                .withExecutionStatus(INPROGRESS)
                .withShouldRetry(false)
                .withAssignedTaskStartTime(clock.zonedDateTime().plusSeconds(delaySecs))
                .build();
    }

    private static boolean retriesExhausted(final ExecutionInfo executionInfo) {
        final Integer remaining = executionInfo.getRetryAttemptsRemaining();
        return remaining != null && remaining <= 0
                || executionInfo.getJobData().getBoolean(KEY_FINAL_ATTEMPT, false);
    }

    private static boolean isLastRetry(final ExecutionInfo executionInfo) {
        final Integer remaining = executionInfo.getRetryAttemptsRemaining();
        return remaining != null && remaining == 1;
    }
}
