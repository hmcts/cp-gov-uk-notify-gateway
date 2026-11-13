package uk.gov.hmcts.cp.notification.acceptance.steps;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Then;
import org.slf4j.event.KeyValuePair;
import org.springframework.beans.factory.annotation.Autowired;

import uk.gov.hmcts.cp.notification.logging.CapturedLogs;
import uk.gov.hmcts.cp.notification.logging.NotificationOutcomeLogger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;

/**
 * Checks the log line an alert reads to tell the team that owns the reply queue about a rejected or failed
 * notification (PEG-3718, PEG-3719).
 */
public class NotificationOutcomeSteps {

    @Autowired
    private ScenarioContext context;

    private CapturedLogs capturedLogs;

    @Before
    public void captureNotificationOutcomeLogs() {
        capturedLogs = captureLogsOf(NotificationOutcomeLogger.class);
    }

    @After
    public void stopCapturingNotificationOutcomeLogs() {
        capturedLogs.close();
    }

    @Then("the rejection is logged for the originator's reply queue as {string}")
    public void the_rejection_is_logged_for_the_originators_reply_queue_as(final String outcomeReason) {
        anOutcomeIsLogged("rejected", outcomeReason);
    }

    @Then("the failure is logged for the originator's reply queue as {string}")
    public void the_failure_is_logged_for_the_originators_reply_queue_as(final String outcomeReason) {
        anOutcomeIsLogged("failed", outcomeReason);
    }

    private void anOutcomeIsLogged(final String notificationOutcome, final String outcomeReason) {
        final String replyQueue = context.getReplyQueue();
        await().atMost(ofSeconds(60)).untilAsserted(() -> {
            final List<ILoggingEvent> loggingEvents = capturedLogs.events();
            final List<Map<String, Object>> loggedFields = loggingEvents.stream()
                    .map(this::fieldsOf)
                    .toList();
            assertThat(loggedFields)
                    .as("a '%s' line for reply queue '%s' with reason '%s'", notificationOutcome, replyQueue, outcomeReason)
                    .anySatisfy(fields -> {
                        assertThat(fields.get("replyQueue")).isEqualTo(replyQueue);
                        assertThat(fields.get("notificationOutcome")).isEqualTo(notificationOutcome);
                        assertThat(fields.get("outcomeReason")).isEqualTo(outcomeReason);
                    });
        });
    }

    private Map<String, Object> fieldsOf(final ILoggingEvent loggingEvent) {
        final Map<String, Object> fields = new HashMap<>();
        final List<KeyValuePair> keyValuePairs = loggingEvent.getKeyValuePairs();
        for (final KeyValuePair keyValuePair : keyValuePairs) {
            fields.put(keyValuePair.key, keyValuePair.value);
        }
        return fields;
    }
}
