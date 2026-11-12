package uk.gov.hmcts.cp.notification.logging;

import static uk.gov.hmcts.cp.notification.logging.DependencyCallField.CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.DependencyCallField.DEPENDENCY;
import static uk.gov.hmcts.cp.notification.logging.DependencyCallField.OPERATION;
import static uk.gov.hmcts.cp.notification.logging.DependencyCallField.OUTCOME;
import static uk.gov.hmcts.cp.notification.logging.DependencyCallField.STATUS_CODE;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Logs a message together with a {@link DependencyCall}'s fields (see {@link DependencyCallField}; the status code
 * only when there is one). The JSON encoder writes them as top-level fields, so that an alert can count calls and
 * failures per dependency without depending on the wording of the message (PEG-3720).
 */
@Component
public class DependencyCallLogger {
    private final Logger logger = LoggerFactory.getLogger(DependencyCallLogger.class);

    public void info(final DependencyCall dependencyCall, final String message, final Object... arguments) {
        final LoggingEventBuilder loggingEventBuilder = logger.atInfo();
        addFieldsOf(dependencyCall, loggingEventBuilder);
        loggingEventBuilder.log(message, arguments);
    }

    public void warn(final DependencyCall dependencyCall, final String message, final Object... arguments) {
        final LoggingEventBuilder loggingEventBuilder = logger.atWarn();
        addFieldsOf(dependencyCall, loggingEventBuilder);
        loggingEventBuilder.log(message, arguments);
    }

    private void addFieldsOf(final DependencyCall dependencyCall, final LoggingEventBuilder loggingEventBuilder) {
        final Dependency dependency = dependencyCall.dependency();
        final Operation operation = dependencyCall.operation();
        final Channel channel = dependencyCall.channel();
        final CallOutcome callOutcome = dependencyCall.callOutcome();
        final Optional<Integer> statusCode = dependencyCall.statusCode();
        loggingEventBuilder.addKeyValue(DEPENDENCY.fieldName(), dependency.fieldValue());
        loggingEventBuilder.addKeyValue(OPERATION.fieldName(), operation.fieldValue());
        loggingEventBuilder.addKeyValue(CHANNEL.fieldName(), channel.fieldValue());
        loggingEventBuilder.addKeyValue(OUTCOME.fieldName(), callOutcome.fieldValue());
        if (statusCode.isPresent()) {
            loggingEventBuilder.addKeyValue(STATUS_CODE.fieldName(), statusCode.get());
        }
    }
}
