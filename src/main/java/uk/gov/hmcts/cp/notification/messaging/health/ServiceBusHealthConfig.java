package uk.gov.hmcts.cp.notification.messaging.health;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import uk.gov.hmcts.cp.notification.logging.QueueReachabilityLogger;
import uk.gov.hmcts.cp.notification.messaging.ConditionalOnServiceBusConfigured;
import uk.gov.hmcts.cp.notification.messaging.ServiceBusClientBuilderFactory;
import uk.gov.hmcts.cp.notification.messaging.ServiceBusProperties;
import uk.gov.hmcts.cp.notification.messaging.ServiceBusProperties.HealthCheck;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import static java.lang.Thread.ofPlatform;
import static java.util.List.of;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.stream.Collectors.toMap;
import static org.springframework.boot.health.contributor.CompositeHealthContributor.fromMap;

/**
 * Service Bus readiness (decision log D6): the "serviceBus" health component, with one sub-component per command
 * queue, is UP only while every command queue can be reached.
 */
@Configuration
@ConditionalOnServiceBusConfigured
@EnableConfigurationProperties(ServiceBusProperties.class)
public class ServiceBusHealthConfig {
    public static final String SERVICE_BUS_HEALTH = "serviceBus";

    private final ServiceBusClientBuilderFactory serviceBusClientBuilderFactory;
    private final List<String> commandQueueNames;
    private final HealthCheck healthCheck;
    private final Clock clock;

    public ServiceBusHealthConfig(
            final ServiceBusClientBuilderFactory serviceBusClientBuilderFactory,
            final ServiceBusProperties serviceBusProperties,
            final Clock clock) {
        this.serviceBusClientBuilderFactory = serviceBusClientBuilderFactory;
        this.commandQueueNames = of(
                serviceBusProperties.commandQueue(),
                serviceBusProperties.letterCommandQueue());
        this.healthCheck = serviceBusProperties.healthCheck();
        this.clock = clock;
    }

    // The poller shuts down its check scheduler when it is closed.
    @SuppressWarnings("PMD.CloseResource")
    @Bean(destroyMethod = "close")
    public QueueReachabilityPoller queueReachabilityPoller(final QueueReachabilityLogger queueReachabilityLogger) {
        final List<QueueReachabilityCheck> queueReachabilityChecks = commandQueueNames.stream()
                .map(queueName -> new QueueReachabilityCheck(
                        queueName, peekClientFor(queueName), queueReachabilityLogger, clock))
                .toList();
        final ThreadFactory checkThreadFactory = ofPlatform()
                .name("queue-reachability-check")
                .daemon(true)
                .factory();
        final ScheduledExecutorService checkScheduler = newSingleThreadScheduledExecutor(checkThreadFactory);

        final QueueReachabilityPoller queueReachabilityPoller =
                new QueueReachabilityPoller(queueReachabilityChecks, checkScheduler, healthCheck.interval());
        queueReachabilityPoller.start();
        return queueReachabilityPoller;
    }

    @Bean(SERVICE_BUS_HEALTH)
    public CompositeHealthContributor serviceBusHealth(final QueueReachabilityPoller queueReachabilityPoller) {
        final Duration successfulPeekCountsFor = twoRoundsOfChecks();
        final Map<String, CommandQueueHealthIndicator> healthIndicatorsByQueueName = queueReachabilityPoller
                .getQueueReachabilityChecks()
                .stream()
                .collect(toMap(
                        QueueReachabilityCheck::getQueueName,
                        queueReachabilityCheck -> new CommandQueueHealthIndicator(
                                queueReachabilityCheck, successfulPeekCountsFor, clock)));
        return fromMap(healthIndicatorsByQueueName);
    }

    private Duration twoRoundsOfChecks() {
        final Duration everyPeekTimingOut = healthCheck.timeout().multipliedBy(commandQueueNames.size());
        final Duration longestRoundOfChecks = healthCheck.interval().plus(everyPeekTimingOut);
        return longestRoundOfChecks.multipliedBy(2);
    }

    private ServiceBusReceiverClient peekClientFor(final String queueName) {
        final AmqpRetryOptions oneAttemptWithinTheTimeout = new AmqpRetryOptions()
                .setMaxRetries(0)
                .setTryTimeout(healthCheck.timeout());
        return serviceBusClientBuilderFactory
                .authenticatedBuilder()
                .retryOptions(oneAttemptWithinTheTimeout)
                .receiver()
                .queueName(queueName)
                .buildClient();
    }
}
