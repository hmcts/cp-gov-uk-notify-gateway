package uk.gov.hmcts.cp.notification.task;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.LogField.CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.LogField.DEPENDENCY;
import static uk.gov.hmcts.cp.notification.logging.LogField.OPERATION;
import static uk.gov.hmcts.cp.notification.logging.LogField.OUTCOME;
import static uk.gov.hmcts.cp.notification.logging.LogField.STATUS_CODE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;
import static uk.gov.hmcts.cp.notification.task.RetryCause.OFFICE365_SEND;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.logging.CallOutcome;
import uk.gov.hmcts.cp.notification.logging.Dependency;
import uk.gov.hmcts.cp.notification.logging.Operation;
import uk.gov.hmcts.cp.notification.sender.Office365Exception;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the email task does when sending through Office 365 fails. A failure that may recover is retried by
 * {@link TransientFailurePolicy}. Any other is logged with the call's fields (PEG-3720) and the notification recorded
 * as FAILED, rejected by the provider.
 */
@Component
public class Office365SendFailureHandler {
    private static final String EXHAUSTED_REASON = "Office 365 send did not recover within the retry window";

    private final Logger logger = getLogger(Office365SendFailureHandler.class);

    private final TransientFailurePolicy failurePolicy;
    private final FailedNotificationRecorder failedNotificationRecorder;

    public Office365SendFailureHandler(
            final TransientFailurePolicy failurePolicy,
            final FailedNotificationRecorder failedNotificationRecorder) {
        this.failurePolicy = failurePolicy;
        this.failedNotificationRecorder = failedNotificationRecorder;
    }

    public ExecutionInfo sendFailed(
            final UUID notificationId,
            final Office365Exception office365Exception,
            final ExecutionInfo executionInfo,
            final List<Long> retryDurationsSecs) {
        final ExecutionInfo result;
        final Optional<Integer> statusCode = ofNullable(office365Exception.getHttpStatus());
        if (office365Exception.isPermanent()) {
            logSendFailedPermanently(notificationId, statusCode);
            final NotificationFailure notificationFailure = new NotificationFailure(
                    notificationId,
                    EMAIL,
                    office365Exception.getHttpStatus(),
                    office365Exception.getMessage(),
                    PROVIDER_REJECTED);
            result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
        } else {
            final TransientFailure transientFailure = new TransientFailure(
                    notificationId,
                    OFFICE365_SEND,
                    EMAIL,
                    statusCode,
                    office365Exception,
                    EXHAUSTED_REASON);
            result = failurePolicy.retryOrFail(transientFailure, executionInfo, retryDurationsSecs);
        }
        return result;
    }

    private void logSendFailedPermanently(final UUID notificationId, final Optional<Integer> statusCode) {
        final Optional<String> statusCodeText = statusCode.map(String::valueOf);
        final String message = format(
                "Permanent Office 365 send failure for notification '%s' (http '%s') — marking FAILED",
                notificationId, statusCodeText.orElse("none"));
        final LoggingEventBuilder loggingEventBuilder = logger.atWarn()
                .addKeyValue(DEPENDENCY, Dependency.MICROSOFT_365.getFieldValue())
                .addKeyValue(OPERATION, Operation.SEND.getFieldValue())
                .addKeyValue(CHANNEL, EMAIL.getFieldValue())
                .addKeyValue(OUTCOME, CallOutcome.PERMANENT_FAILURE.getFieldValue());
        statusCode.ifPresent(code -> loggingEventBuilder.addKeyValue(STATUS_CODE, code));
        loggingEventBuilder.log(message);
    }
}
