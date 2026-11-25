package uk.gov.hmcts.cp.notification.task;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.Dependency.BLOB_STORAGE;
import static uk.gov.hmcts.cp.notification.logging.LogField.CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.LogField.DEPENDENCY;
import static uk.gov.hmcts.cp.notification.logging.LogField.OPERATION;
import static uk.gov.hmcts.cp.notification.logging.LogField.OUTCOME;
import static uk.gov.hmcts.cp.notification.logging.LogField.STATUS_CODE;
import static uk.gov.hmcts.cp.notification.task.RetryCause.ATTACHMENT_DOWNLOAD;
import static uk.gov.hmcts.cp.notification.task.RetryCause.MATERIAL_DOWNLOAD;
import static uk.gov.hmcts.cp.notification.task.RetryCause.SAS_DOWNLOAD;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.blob.PermanentBlobException;
import uk.gov.hmcts.cp.notification.blob.TransientBlobException;
import uk.gov.hmcts.cp.notification.logging.CallOutcome;
import uk.gov.hmcts.cp.notification.logging.Channel;
import uk.gov.hmcts.cp.notification.logging.Dependency;
import uk.gov.hmcts.cp.notification.logging.Operation;
import uk.gov.hmcts.cp.notification.logging.OutcomeReason;
import uk.gov.hmcts.cp.notification.logging.OutcomeReasonClassifier;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a send task does when it cannot download the document it sends: an email attachment from blob storage
 * ({@code fileUri}), or a Material document ({@code materialUrl}) for an email or a letter. A failure that may recover
 * is retried by {@link TransientFailurePolicy}. Any other is logged with the call's fields (PEG-3720) and the
 * notification recorded as FAILED with the outcome reason its status code gives.
 */
@Component
public class AttachmentDownloadFailureHandler {
    private static final String BLOB_EXHAUSTED_REASON = "Attachment download did not recover within the retry window";
    private static final String MATERIAL_EMAIL_EXHAUSTED_REASON =
            "Material attachment download did not recover within the retry window";
    private static final String MATERIAL_LETTER_EXHAUSTED_REASON =
            "Material letter download did not recover within the retry window";

    private final Logger logger = getLogger(AttachmentDownloadFailureHandler.class);

    private final TransientFailurePolicy failurePolicy;
    private final FailedNotificationRecorder failedNotificationRecorder;
    private final OutcomeReasonClassifier outcomeReasonClassifier;

    public AttachmentDownloadFailureHandler(
            final TransientFailurePolicy failurePolicy,
            final FailedNotificationRecorder failedNotificationRecorder,
            final OutcomeReasonClassifier outcomeReasonClassifier) {
        this.failurePolicy = failurePolicy;
        this.failedNotificationRecorder = failedNotificationRecorder;
        this.outcomeReasonClassifier = outcomeReasonClassifier;
    }

    /** The email attachment can never be downloaded from blob storage. */
    public ExecutionInfo blobDownloadFailed(
            final UUID notificationId,
            final PermanentBlobException permanentBlobException,
            final ExecutionInfo executionInfo) {
        final Optional<Integer> statusCode = ofNullable(permanentBlobException.getStatusCode());
        // The message may name the fileUri, so it is recorded but not logged.
        final String message = format(
                "Permanent attachment failure for notification '%s' — marking FAILED, no retry", notificationId);
        logDownloadFailedPermanently(BLOB_STORAGE, EMAIL, statusCode, message);
        final OutcomeReason outcomeReason = outcomeReasonClassifier.attachmentFailed(statusCode);
        final NotificationFailure notificationFailure = new NotificationFailure(
                notificationId,
                EMAIL,
                permanentBlobException.getStatusCode(),
                permanentBlobException.getMessage(),
                outcomeReason);
        return failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
    }

    /** The email attachment could not be downloaded from blob storage, but a retry may succeed. */
    public ExecutionInfo blobDownloadFailed(
            final UUID notificationId,
            final TransientBlobException transientBlobException,
            final ExecutionInfo executionInfo,
            final List<Long> retryDurationsSecs) {
        final TransientFailure transientFailure = new TransientFailure(
                notificationId,
                ATTACHMENT_DOWNLOAD,
                EMAIL,
                transientBlobException.getStatusCode(),
                transientBlobException,
                BLOB_EXHAUSTED_REASON);
        return failurePolicy.retryOrFail(transientFailure, executionInfo, retryDurationsSecs);
    }

    /** The Material document could not be obtained: from Material, or from blob storage through its SAS link. */
    public ExecutionInfo materialDownloadFailed(
            final UUID notificationId,
            final Channel channel,
            final MaterialAttachmentException materialAttachmentException,
            final ExecutionInfo executionInfo,
            final List<Long> retryDurationsSecs) {
        final ExecutionInfo result;
        final Optional<Integer> statusCode = ofNullable(materialAttachmentException.getStatusCode());
        final Dependency dependency = materialAttachmentException.getDependency();
        if (materialAttachmentException.isRetryable()) {
            final RetryCause retryCause = dependency == BLOB_STORAGE ? SAS_DOWNLOAD : MATERIAL_DOWNLOAD;
            final TransientFailure transientFailure = new TransientFailure(
                    notificationId,
                    retryCause,
                    channel,
                    statusCode,
                    materialAttachmentException,
                    materialExhaustedReasonFor(channel));
            result = failurePolicy.retryOrFail(transientFailure, executionInfo, retryDurationsSecs);
        } else {
            final String failureMessage = materialAttachmentException.getMessage();
            final String message = format(
                    "Permanent Material document failure for %s notification '%s' ('%s') — marking FAILED, no retry",
                    channel.getFieldValue(), notificationId, failureMessage);
            logDownloadFailedPermanently(dependency, channel, statusCode, message);
            final OutcomeReason outcomeReason = outcomeReasonClassifier.attachmentFailed(statusCode);
            final NotificationFailure notificationFailure = new NotificationFailure(
                    notificationId,
                    channel,
                    materialAttachmentException.getStatusCode(),
                    failureMessage,
                    outcomeReason);
            result = failedNotificationRecorder.recordFailed(notificationFailure, executionInfo);
        }
        return result;
    }

    /** Sent to the requesting team in {@code notification-failed}, so each keeps the text it has always had. */
    private String materialExhaustedReasonFor(final Channel channel) {
        final String exhaustedReason;
        if (channel == LETTER) {
            exhaustedReason = MATERIAL_LETTER_EXHAUSTED_REASON;
        } else {
            exhaustedReason = MATERIAL_EMAIL_EXHAUSTED_REASON;
        }
        return exhaustedReason;
    }

    private void logDownloadFailedPermanently(
            final Dependency dependency,
            final Channel channel,
            final Optional<Integer> statusCode,
            final String message) {
        final LoggingEventBuilder loggingEventBuilder = logger.atWarn()
                .addKeyValue(DEPENDENCY, dependency.getFieldValue())
                .addKeyValue(OPERATION, Operation.DOWNLOAD.getFieldValue())
                .addKeyValue(CHANNEL, channel.getFieldValue())
                .addKeyValue(OUTCOME, CallOutcome.PERMANENT_FAILURE.getFieldValue());
        statusCode.ifPresent(code -> loggingEventBuilder.addKeyValue(STATUS_CODE, code));
        loggingEventBuilder.log(message);
    }
}
