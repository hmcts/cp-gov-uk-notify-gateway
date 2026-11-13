package uk.gov.hmcts.cp.notification.logging;

import static uk.gov.hmcts.cp.notification.logging.NotificationOutcomeField.NOTIFICATION_CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.NotificationOutcomeField.NOTIFICATION_OUTCOME;
import static uk.gov.hmcts.cp.notification.logging.NotificationOutcomeField.OUTCOME_REASON;
import static uk.gov.hmcts.cp.notification.logging.NotificationOutcomeField.REPLY_QUEUE;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/**
 * Logs a message together with a {@link NotificationOutcomeEvent}'s fields (see {@link NotificationOutcomeField}).
 * The JSON encoder writes them as top-level fields, so that an alert can count rejected and failed notifications for
 * each reply queue without depending on the wording of the message (PEG-3718, PEG-3719).
 */
@Component
public class NotificationOutcomeLogger {
    private final Logger logger = LoggerFactory.getLogger(NotificationOutcomeLogger.class);

    public void warn(final NotificationOutcomeEvent notificationOutcomeEvent, final String message) {
        final LoggingEventBuilder loggingEventBuilder = logger.atWarn();
        final LoggingEventBuilder loggingEventBuilderWithFields =
                withFieldsOf(notificationOutcomeEvent, loggingEventBuilder);
        loggingEventBuilderWithFields.log(message);
    }

    private LoggingEventBuilder withFieldsOf(
            final NotificationOutcomeEvent notificationOutcomeEvent, final LoggingEventBuilder loggingEventBuilder) {
        final NotificationOutcome notificationOutcome = notificationOutcomeEvent.notificationOutcome();
        final OutcomeReason outcomeReason = notificationOutcomeEvent.outcomeReason();
        final Channel channel = notificationOutcomeEvent.channel();
        final String replyQueue = notificationOutcomeEvent.replyQueue();
        final String notificationOutcomeValue = notificationOutcome.getFieldValue();
        final String outcomeReasonValue = outcomeReason.getFieldValue();
        final String channelValue = channel.getFieldValue();

        LoggingEventBuilder loggingEventBuilderWithFields = withField(loggingEventBuilder, REPLY_QUEUE, replyQueue);
        loggingEventBuilderWithFields =
                withField(loggingEventBuilderWithFields, NOTIFICATION_OUTCOME, notificationOutcomeValue);
        loggingEventBuilderWithFields = withField(loggingEventBuilderWithFields, OUTCOME_REASON, outcomeReasonValue);
        loggingEventBuilderWithFields = withField(loggingEventBuilderWithFields, NOTIFICATION_CHANNEL, channelValue);
        return loggingEventBuilderWithFields;
    }

    private LoggingEventBuilder withField(
            final LoggingEventBuilder loggingEventBuilder,
            final NotificationOutcomeField notificationOutcomeField,
            final String value) {
        final String fieldName = notificationOutcomeField.getFieldName();
        return loggingEventBuilder.addKeyValue(fieldName, value);
    }
}
