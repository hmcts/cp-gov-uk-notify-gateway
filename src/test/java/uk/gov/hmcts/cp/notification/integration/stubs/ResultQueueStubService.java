package uk.gov.hmcts.cp.notification.integration.stubs;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;

import java.time.Duration;
import java.util.UUID;

import uk.gov.hmcts.cp.notification.integration.stubs.support.ContractSchema;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.stubs.ASBTestClient.anAsbTestClient;

/**
 * Stands in for a future originator context consuming its ReplyTo result queue. Fluent reader over the
 * ASB emulator so tests assert the received result event field-by-field (subject = event name, body =
 * golden-master payload) rather than merely that something arrived.
 */
public final class ResultQueueStubService {

    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(60);

    private final ASBTestClient queue;
    private ServiceBusReceivedMessage received;

    private ResultQueueStubService(final String queueName) {
        this.queue = anAsbTestClient(queueName);
    }

    public static ResultQueueStubService aResultQueue(final String queueName) {
        return new ResultQueueStubService(queueName);
    }

    public ResultQueueStubService receivesResultEvent() {
        received = queue.receiveMessage(RECEIVE_TIMEOUT);
        assertThat(received).as("a result event should have been published to the reply queue").isNotNull();
        return this;
    }

    public ResultQueueStubService withSubject(final String expectedEventName) {
        assertThat(received.getSubject())
                .as("result event ASB subject carries the golden-master event name")
                .isEqualTo(expectedEventName);
        return this;
    }

    public ResultQueueStubService withField(final String jsonPath, final Object expectedValue) {
        assertThatJson(body()).node(jsonPath).isEqualTo(expectedValue);
        return this;
    }

    public ResultQueueStubService withFieldPresent(final String jsonPath) {
        assertThatJson(body()).node(jsonPath).isPresent();
        return this;
    }

    public ResultQueueStubService withFieldAbsent(final String jsonPath) {
        assertThatJson(body()).node(jsonPath).isAbsent();
        return this;
    }

    public ResultQueueStubService conformsTo(final String contractSchema) {
        ContractSchema.assertConformsTo(contractSchema, body());
        return this;
    }

    public void drain() {
        while (queue.receiveMessage(Duration.ofMillis(500)) != null) {
            // discard any residual events left by a previous scenario
        }
    }

    public void receivesNoResultEvent() {
        final String tracer = "tracer-" + UUID.randomUUID();
        queue.send(tracer);
        final ServiceBusReceivedMessage first = queue.receiveMessage(RECEIVE_TIMEOUT);
        assertThat(first).as("the tracer just enqueued must come back").isNotNull();
        assertThat(first.getBody().toString())
                .as("the reply queue holds only the tracer — no result event was published ahead of it")
                .isEqualTo(tracer);
    }

    public String body() {
        return received.getBody().toString();
    }
}
