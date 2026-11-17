package uk.gov.hmcts.cp.notification.acceptance.steps;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Then;
import org.slf4j.event.KeyValuePair;
import org.springframework.beans.factory.annotation.Autowired;

import uk.gov.hmcts.cp.notification.logging.CapturedLogs;
import uk.gov.hmcts.cp.notification.messaging.ValidationFailureNotifier;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.time.Duration.ofSeconds;
import static java.util.Optional.ofNullable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;

/**
 * Checks the log line an alert reads to tell the team that owns the reply queue about a rejected or failed
 * notification (PEG-3718, PEG-3719). A rejection is logged by {@link ValidationFailureNotifier} and a failure by
 * {@link NotificationStatusService}.
 */
public class NotificationOutcomeSteps {

    @Autowired
    private ScenarioContext context;

    private CapturedLogs rejectionLogs;
    private CapturedLogs failureLogs;

    @Before
    public void captureNotificationOutcomeLogs() {
        rejectionLogs = captureLogsOf(ValidationFailureNotifier.class);
        failureLogs = captureLogsOf(NotificationStatusService.class);
    }

    @After
    public void stopCapturingNotificationOutcomeLogs() {
        rejectionLogs.close();
        failureLogs.close();
    }

    @Then("the rejection is logged for the originator's reply queue as {string}")
    public void the_rejection_is_logged_for_the_originators_reply_queue_as(final String outcomeReason) {
        anOutcomeIsLogged(rejectionLogs, "rejected", outcomeReason);
    }

    @Then("the failure is logged for the originator's reply queue as {string}")
    public void the_failure_is_logged_for_the_originators_reply_queue_as(final String outcomeReason) {
        anOutcomeIsLogged(failureLogs, "failed", outcomeReason);
    }

    private void anOutcomeIsLogged(
            final CapturedLogs capturedLogs, final String notificationOutcome, final String outcomeReason) {
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

    /** Logback gives no list at all, rather than an empty one, for a line without fields, such as "marked SENT". */
    private Map<String, Object> fieldsOf(final ILoggingEvent loggingEvent) {
        final Map<String, Object> fields = new HashMap<>();
        final Optional<List<KeyValuePair>> possibleKeyValuePairs = ofNullable(loggingEvent.getKeyValuePairs());
        final List<KeyValuePair> keyValuePairs = possibleKeyValuePairs.orElse(List.of());
        for (final KeyValuePair keyValuePair : keyValuePairs) {
            fields.put(keyValuePair.key, keyValuePair.value);
        }
        return fields;
    }
}
