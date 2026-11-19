package uk.gov.hmcts.cp.notification.messaging;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConditionalOnExpression(
        "!'${cp.notification.servicebus.connection-string:}'.trim().isEmpty()"
                + " || !'${cp.notification.servicebus.namespace:}'.trim().isEmpty()")
public class ServiceBusConfig {
    @Bean(destroyMethod = "close")
    /* default */ ServiceBusProcessorClient sendEmailProcessorClient(
            @Value("${cp.notification.servicebus.connection-string:}") final String connectionString,
            @Value("${cp.notification.servicebus.namespace:}") final String namespace,
            @Value("${cp.notification.servicebus.command-queue}") final String commandQueue,
            @Value("${cp.notification.servicebus.max-concurrent-calls:15}") final int maxConcurrentCalls,
            final SendEmailConsumer consumer) {
        final ServiceBusProcessorClient client = authenticate(new ServiceBusClientBuilder(), connectionString, namespace)
                .processor()
                .queueName(commandQueue)
                // SDK default is 1 (serial); 15 matches the legacy MDB maxSession.
                .maxConcurrentCalls(maxConcurrentCalls)
                .disableAutoComplete()
                .processMessage(consumer::processMessage)
                .processError(consumer::processError)
                .buildProcessorClient();

        client.start();
        return client;
    }

    // One command queue per command type (architecture-design: "shared command queue per command type").
    @Bean(destroyMethod = "close")
    /* default */ ServiceBusProcessorClient sendLetterProcessorClient(
            @Value("${cp.notification.servicebus.connection-string:}") final String connectionString,
            @Value("${cp.notification.servicebus.namespace:}") final String namespace,
            @Value("${cp.notification.servicebus.letter-command-queue}") final String letterCommandQueue,
            @Value("${cp.notification.servicebus.max-concurrent-calls:15}") final int maxConcurrentCalls,
            final SendLetterConsumer consumer) {
        final ServiceBusProcessorClient client = authenticate(new ServiceBusClientBuilder(), connectionString, namespace)
                .processor()
                .queueName(letterCommandQueue)
                .maxConcurrentCalls(maxConcurrentCalls)
                .disableAutoComplete()
                .processMessage(consumer::processMessage)
                .processError(consumer::processError)
                .buildProcessorClient();

        client.start();
        return client;
    }

    // Readiness (decision log D6): both command queues must be reachable. The peek clients share one builder, so
    // they share one AMQP connection; each peek is a single attempt that gives up after the timeout.
    @Bean(name = ServiceBusHealthIndicator.NAME, destroyMethod = "close")
    /* default */ ServiceBusHealthIndicator serviceBusHealthIndicator(
            @Value("${cp.notification.servicebus.connection-string:}") final String connectionString,
            @Value("${cp.notification.servicebus.namespace:}") final String namespace,
            @Value("${cp.notification.servicebus.command-queue}") final String commandQueue,
            @Value("${cp.notification.servicebus.letter-command-queue}") final String letterCommandQueue,
            @Value("${cp.notification.servicebus.health-check.interval:30s}") final Duration interval,
            @Value("${cp.notification.servicebus.health-check.timeout:10s}") final Duration timeout,
            final Clock clock) {
        final AmqpRetryOptions oneAttemptWithinTheTimeout = new AmqpRetryOptions()
                .setMaxRetries(0)
                .setTryTimeout(timeout);
        final ServiceBusClientBuilder peekClientBuilder = authenticate(
                new ServiceBusClientBuilder().retryOptions(oneAttemptWithinTheTimeout), connectionString, namespace);
        final Map<String, ServiceBusReceiverClient> peekClients = new LinkedHashMap<>();
        for (final String queue : List.of(commandQueue, letterCommandQueue)) {
            peekClients.put(queue, peekClientBuilder.receiver().queueName(queue).buildClient());
        }
        // A peek counts until two full rounds of checks could have passed, so one slow round doesn't fail readiness.
        final Duration reachedCountsFor = interval.plus(timeout.multipliedBy(peekClients.size())).multipliedBy(2);
        return new ServiceBusHealthIndicator(peekClients, reachedCountsFor, clock);
    }

    private static ServiceBusClientBuilder authenticate(
            final ServiceBusClientBuilder builder, final String connectionString, final String namespace) {
        final ServiceBusClientBuilder authenticated;
        if (StringUtils.hasText(connectionString)) {
            authenticated = builder.connectionString(connectionString);
        } else {
            authenticated = builder
                    .fullyQualifiedNamespace(namespace)
                    .credential(new DefaultAzureCredentialBuilder().build());
        }
        return authenticated;
    }
}
