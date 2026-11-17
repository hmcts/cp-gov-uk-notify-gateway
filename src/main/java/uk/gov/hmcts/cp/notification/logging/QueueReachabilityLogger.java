package uk.gov.hmcts.cp.notification.logging;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNullElse;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * The log lines for a command queue becoming reachable or unreachable, worded for an operator. Logged only when
 * that changes, not on every check.
 */
@Component
public class QueueReachabilityLogger {
    private final Logger logger = getLogger(QueueReachabilityLogger.class);

    public void reachable(final String queueName) {
        final String message = """
                Service Bus queue '%s' can be reached. \
                This instance can read notification requests from it, \
                so this queue no longer stops it reporting itself ready."""
                .formatted(queueName);
        logger.info(message);
    }

    public void unreachable(final String queueName, final RuntimeException peekFailure) {
        final Class<? extends RuntimeException> peekFailureType = peekFailure.getClass();
        final String peekFailureDescription = requireNonNullElse(peekFailure.getMessage(), peekFailureType.getName());
        final String message = """
                Service Bus queue '%s' cannot be reached: '%s'. \
                This instance reports itself not ready until the queue can be reached again. \
                Check that the queue exists in the Service Bus namespace \
                and that this service is allowed to read from it."""
                .formatted(queueName, peekFailureDescription);
        logger.warn(message);
    }
}
