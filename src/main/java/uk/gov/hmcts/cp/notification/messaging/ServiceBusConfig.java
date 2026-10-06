package uk.gov.hmcts.cp.notification.messaging;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

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
