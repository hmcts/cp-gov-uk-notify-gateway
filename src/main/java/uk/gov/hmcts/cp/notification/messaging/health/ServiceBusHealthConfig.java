package uk.gov.hmcts.cp.notification.messaging.health;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import uk.gov.hmcts.cp.notification.messaging.ConditionalOnServiceBusConfigured;
import uk.gov.hmcts.cp.notification.messaging.ServiceBusClientBuilderFactory;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.util.List;

import static java.util.Map.of;
import static org.springframework.boot.health.contributor.CompositeHealthContributor.fromMap;

/**
 * Service Bus readiness (decision log D6): the "serviceBus" health component is UP only while both command queues
 * are being consumed, with one sub-component per queue.
 */
@Configuration
@ConditionalOnServiceBusConfigured
@EnableConfigurationProperties(ServiceBusHealthCheckProperties.class)
public class ServiceBusHealthConfig {
    private final ServiceBusClientBuilderFactory serviceBusClientBuilderFactory;
    private final ServiceBusHealthCheckProperties healthCheckProperties;
    private final Clock clock;

    public ServiceBusHealthConfig(
            final ServiceBusClientBuilderFactory serviceBusClientBuilderFactory,
            final ServiceBusHealthCheckProperties healthCheckProperties,
            final Clock clock) {
        this.serviceBusClientBuilderFactory = serviceBusClientBuilderFactory;
        this.healthCheckProperties = healthCheckProperties;
        this.clock = clock;
    }

    @Bean(destroyMethod = "close")
    /* default */ QueueReachabilityCheck emailCommandQueueReachabilityCheck(
            @Value("${cp.notification.servicebus.command-queue}") final String emailCommandQueueName,
            final Logger queueReachabilityCheckLogger) {
        return new QueueReachabilityCheck(
                emailCommandQueueName, peekClientFor(emailCommandQueueName), queueReachabilityCheckLogger);
    }

    @Bean(destroyMethod = "close")
    /* default */ QueueReachabilityCheck letterCommandQueueReachabilityCheck(
            @Value("${cp.notification.servicebus.letter-command-queue}") final String letterCommandQueueName,
            final Logger queueReachabilityCheckLogger) {
        return new QueueReachabilityCheck(
                letterCommandQueueName, peekClientFor(letterCommandQueueName), queueReachabilityCheckLogger);
    }

    @Bean
    /* default */ QueueReachabilityPoller queueReachabilityPoller(
            final List<QueueReachabilityCheck> queueReachabilityChecks) {
        return new QueueReachabilityPoller(queueReachabilityChecks, clock);
    }

    @Bean
    /* default */ CompositeHealthContributor serviceBusHealthContributor(
            @Value("${cp.notification.servicebus.command-queue}") final String emailCommandQueueName,
            @Value("${cp.notification.servicebus.letter-command-queue}") final String letterCommandQueueName,
            @Qualifier("sendEmailProcessorClient") final ServiceBusProcessorClient sendEmailProcessorClient,
            @Qualifier("sendLetterProcessorClient") final ServiceBusProcessorClient sendLetterProcessorClient,
            @Qualifier("emailCommandQueueReachabilityCheck") final QueueReachabilityCheck emailCommandQueueReachabilityCheck,
            @Qualifier("letterCommandQueueReachabilityCheck") final QueueReachabilityCheck letterCommandQueueReachabilityCheck) {
        return fromMap(of(
                emailCommandQueueName,
                commandQueueHealthIndicator(sendEmailProcessorClient, emailCommandQueueReachabilityCheck),
                letterCommandQueueName,
                commandQueueHealthIndicator(sendLetterProcessorClient, letterCommandQueueReachabilityCheck)));
    }

    private CommandQueueHealthIndicator commandQueueHealthIndicator(
            final ServiceBusProcessorClient commandQueueProcessorClient,
            final QueueReachabilityCheck queueReachabilityCheck) {
        return new CommandQueueHealthIndicator(
                commandQueueProcessorClient, queueReachabilityCheck, clock, healthCheckProperties.staleAfter());
    }

    private ServiceBusReceiverClient peekClientFor(final String queueName) {
        return serviceBusClientBuilderFactory.authenticatedBuilder()
                // One attempt with a short timeout: a health check should report a problem, not wait it out.
                .retryOptions(new AmqpRetryOptions().setMaxRetries(0).setTryTimeout(healthCheckProperties.timeout()))
                .receiver()
                .queueName(queueName)
                .buildClient();
    }
}
