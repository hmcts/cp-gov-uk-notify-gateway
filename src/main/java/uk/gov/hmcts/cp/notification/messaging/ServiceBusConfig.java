package uk.gov.hmcts.cp.notification.messaging;

import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnServiceBusConfigured
public class ServiceBusConfig {
    @Bean(destroyMethod = "close")
    /* default */ ServiceBusProcessorClient sendEmailProcessorClient(
            final ServiceBusClientBuilderFactory serviceBusClientBuilderFactory,
            @Value("${cp.notification.servicebus.command-queue}") final String emailCommandQueueName,
            @Value("${cp.notification.servicebus.max-concurrent-calls:15}") final int maxConcurrentCalls,
            final SendEmailConsumer sendEmailConsumer) {
        final ServiceBusProcessorClient sendEmailProcessorClient = serviceBusClientBuilderFactory.authenticatedBuilder()
                .processor()
                .queueName(emailCommandQueueName)
                // SDK default is 1 (serial); 15 matches the legacy MDB maxSession.
                .maxConcurrentCalls(maxConcurrentCalls)
                .disableAutoComplete()
                .processMessage(sendEmailConsumer::processMessage)
                .processError(sendEmailConsumer::processError)
                .buildProcessorClient();

        sendEmailProcessorClient.start();
        return sendEmailProcessorClient;
    }

    // One command queue per command type (architecture-design: "shared command queue per command type").
    @Bean(destroyMethod = "close")
    /* default */ ServiceBusProcessorClient sendLetterProcessorClient(
            final ServiceBusClientBuilderFactory serviceBusClientBuilderFactory,
            @Value("${cp.notification.servicebus.letter-command-queue}") final String letterCommandQueueName,
            @Value("${cp.notification.servicebus.max-concurrent-calls:15}") final int maxConcurrentCalls,
            final SendLetterConsumer sendLetterConsumer) {
        final ServiceBusProcessorClient sendLetterProcessorClient = serviceBusClientBuilderFactory.authenticatedBuilder()
                .processor()
                .queueName(letterCommandQueueName)
                .maxConcurrentCalls(maxConcurrentCalls)
                .disableAutoComplete()
                .processMessage(sendLetterConsumer::processMessage)
                .processError(sendLetterConsumer::processError)
                .buildProcessorClient();

        sendLetterProcessorClient.start();
        return sendLetterProcessorClient;
    }
}
