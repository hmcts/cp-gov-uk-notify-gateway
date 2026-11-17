package uk.gov.hmcts.cp.notification.messaging;

import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnServiceBusConfigured
@EnableConfigurationProperties(ServiceBusProperties.class)
public class ServiceBusConfig {
    @Bean(destroyMethod = "close")
    public ServiceBusProcessorClient sendEmailProcessorClient(
            final ServiceBusClientBuilderFactory serviceBusClientBuilderFactory,
            final ServiceBusProperties serviceBusProperties,
            final SendEmailConsumer sendEmailConsumer) {
        final ServiceBusProcessorClient sendEmailProcessorClient = serviceBusClientBuilderFactory
                .authenticatedBuilder()
                .processor()
                .queueName(serviceBusProperties.commandQueue())
                // SDK default is 1 (serial); 15 matches the legacy MDB maxSession.
                .maxConcurrentCalls(serviceBusProperties.maxConcurrentCalls())
                .disableAutoComplete()
                .processMessage(sendEmailConsumer::processMessage)
                .processError(sendEmailConsumer::processError)
                .buildProcessorClient();

        sendEmailProcessorClient.start();
        return sendEmailProcessorClient;
    }

    // One command queue per command type (architecture-design: "shared command queue per command type").
    @Bean(destroyMethod = "close")
    public ServiceBusProcessorClient sendLetterProcessorClient(
            final ServiceBusClientBuilderFactory serviceBusClientBuilderFactory,
            final ServiceBusProperties serviceBusProperties,
            final SendLetterConsumer sendLetterConsumer) {
        final ServiceBusProcessorClient sendLetterProcessorClient = serviceBusClientBuilderFactory
                .authenticatedBuilder()
                .processor()
                .queueName(serviceBusProperties.letterCommandQueue())
                .maxConcurrentCalls(serviceBusProperties.maxConcurrentCalls())
                .disableAutoComplete()
                .processMessage(sendLetterConsumer::processMessage)
                .processError(sendLetterConsumer::processError)
                .buildProcessorClient();

        sendLetterProcessorClient.start();
        return sendLetterProcessorClient;
    }
}
