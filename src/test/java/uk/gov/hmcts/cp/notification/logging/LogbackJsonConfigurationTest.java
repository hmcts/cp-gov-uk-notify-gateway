package uk.gov.hmcts.cp.notification.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URL;
import java.util.List;
import java.util.Map;

import static ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME;
import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.TRANSIENT_FAILURE;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Dependency.GOV_UK_NOTIFY;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;

/**
 * Encodes a {@link DependencyCallLogger} log line with the encoder from the production {@code logback.xml} and checks
 * that the dependency call's fields come out as top-level JSON fields, which is what the Log Analytics alert queries
 * read (PEG-3720).
 */
class LogbackJsonConfigurationTest {
    private final String notificationId = "7c6a0f4e-0d0e-4c43-9a49-5bd2b4a0a1b2";
    private final TypeReference<Map<String, Object>> jsonObjectType = new TypeReference<>() {
    };
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final LoggerContext loggerContext = new LoggerContext();
    private final DependencyCallLogger dependencyCallLogger = new DependencyCallLogger();

    @BeforeEach
    void configureALoggerContextFromTheProductionLogbackXml() throws JoranException {
        loggerContext.setMDCAdapter(new LogbackMDCAdapter());
        final JoranConfigurator joranConfigurator = new JoranConfigurator();
        joranConfigurator.setContext(loggerContext);
        final URL logbackXml = LogbackJsonConfigurationTest.class.getResource("/logback.xml");
        joranConfigurator.doConfigure(logbackXml);
    }

    @AfterEach
    void stopTheLoggerContext() {
        loggerContext.stop();
    }

    @Test
    void writes_the_dependency_call_fields_as_top_level_json_fields() {
        final DependencyCall dependencyCall = new DependencyCall(GOV_UK_NOTIFY, SEND, EMAIL, TRANSIENT_FAILURE, of(503));
        final ILoggingEvent loggingEvent = loggedEventOf(dependencyCall);

        final Map<String, Object> jsonFields = jsonFieldsOf(loggingEvent);

        assertThat(jsonFields.get("message")).isEqualTo("Transient failure for notificationId '" + notificationId + "'");
        assertThat(jsonFields.get("level")).isEqualTo("INFO");
        assertThat(jsonFields.get("dependency")).isEqualTo("gov-uk-notify");
        assertThat(jsonFields.get("operation")).isEqualTo("send");
        assertThat(jsonFields.get("channel")).isEqualTo("email");
        assertThat(jsonFields.get("outcome")).isEqualTo("transient-failure");
        assertThat(jsonFields.get("statusCode")).isEqualTo(503);
    }

    private ILoggingEvent loggedEventOf(final DependencyCall dependencyCall) {
        try (CapturedLogs capturedLogs = captureLogsOf(DependencyCallLogger.class)) {
            dependencyCallLogger.info(dependencyCall, "Transient failure for notificationId '" + notificationId + "'");
            final List<ILoggingEvent> loggingEvents = capturedLogs.events();
            return loggingEvents.getFirst();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonFieldsOf(final ILoggingEvent loggingEvent) {
        final Logger rootLogger = loggerContext.getLogger(ROOT_LOGGER_NAME);
        final Appender<ILoggingEvent> appender = rootLogger.getAppender("JSON");
        final OutputStreamAppender<ILoggingEvent> outputStreamAppender = (OutputStreamAppender<ILoggingEvent>) appender;
        final Encoder<ILoggingEvent> encoder = outputStreamAppender.getEncoder();
        final byte[] encodedLoggingEvent = encoder.encode(loggingEvent);
        return objectMapper.readValue(encodedLoggingEvent, jsonObjectType);
    }
}
