package uk.gov.hmcts.cp.notification.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class CapturedLogs implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override
        protected void append(final ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    private CapturedLogs(final Class<?> source) {
        this.logger = (Logger) LoggerFactory.getLogger(source);
        appender.start();
        logger.addAppender(appender);
    }

    public static CapturedLogs captureLogsOf(final Class<?> source) {
        return new CapturedLogs(source);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
