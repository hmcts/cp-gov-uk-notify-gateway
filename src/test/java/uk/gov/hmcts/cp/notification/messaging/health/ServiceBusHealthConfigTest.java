package uk.gov.hmcts.cp.notification.messaging.health;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder.ServiceBusReceiverClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributors;
import org.springframework.boot.health.contributor.HealthIndicator;

import uk.gov.hmcts.cp.notification.logging.QueueReachabilityLogger;
import uk.gov.hmcts.cp.notification.messaging.ServiceBusClientBuilderFactory;
import uk.gov.hmcts.cp.notification.messaging.ServiceBusProperties;
import uk.gov.hmcts.cp.notification.messaging.ServiceBusProperties.HealthCheck;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import static java.time.Duration.ofSeconds;
import static java.time.ZonedDateTime.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.quality.Strictness.LENIENT;
import static org.springframework.boot.health.contributor.Status.DOWN;
import static org.springframework.boot.health.contributor.Status.UP;

// Lenient: the poller peeks on its own thread, so the clock may or may not have been read when a test ends.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = LENIENT)
class ServiceBusHealthConfigTest {
    private final ServiceBusProperties serviceBusProperties = new ServiceBusProperties(
            "", "my-namespace.servicebus.windows.net", "ng-send-email", "ng-send-letter", 15,
            new HealthCheck(ofSeconds(30), ofSeconds(10)));
    private final ZonedDateTime checkTime = parse("2026-11-10T10:00:00Z");
    private final ServiceBusClientBuilder serviceBusClientBuilder = mock(ServiceBusClientBuilder.class, RETURNS_SELF);
    private final ServiceBusReceiverClientBuilder receiverClientBuilder =
            mock(ServiceBusReceiverClientBuilder.class, RETURNS_SELF);

    @Mock
    private ServiceBusClientBuilderFactory serviceBusClientBuilderFactory;

    @Mock
    private ServiceBusReceiverClient queuePeekClient;

    @Mock
    private QueueReachabilityLogger queueReachabilityLogger;

    @Mock
    private Clock clock;

    @Captor
    private ArgumentCaptor<String> peekedQueueNames;

    @Captor
    private ArgumentCaptor<AmqpRetryOptions> peekRetryOptions;

    private ServiceBusHealthConfig serviceBusHealthConfig;
    private QueueReachabilityPoller queueReachabilityPoller;

    @BeforeEach
    void startThePollerWithPeekClientsBuiltThroughTheAuthenticatedBuilder() {
        when(serviceBusClientBuilderFactory.authenticatedBuilder()).thenReturn(serviceBusClientBuilder);
        when(serviceBusClientBuilder.receiver()).thenReturn(receiverClientBuilder);
        when(receiverClientBuilder.buildClient()).thenReturn(queuePeekClient);
        when(clock.zonedDateTime()).thenReturn(checkTime);
        serviceBusHealthConfig =
                new ServiceBusHealthConfig(serviceBusClientBuilderFactory, serviceBusProperties, clock);
        queueReachabilityPoller = serviceBusHealthConfig.queueReachabilityPoller(queueReachabilityLogger);
    }

    @AfterEach
    void closeThePoller() {
        queueReachabilityPoller.close();
    }

    @Test
    void should_peek_each_command_queue_once_within_the_configured_timeout() {
        verify(receiverClientBuilder, times(2)).queueName(peekedQueueNames.capture());
        assertThat(peekedQueueNames.getAllValues()).containsExactly("ng-send-email", "ng-send-letter");
        verify(serviceBusClientBuilder, times(2)).retryOptions(peekRetryOptions.capture());
        final List<AmqpRetryOptions> retryOptionsForEachQueue = peekRetryOptions.getAllValues();
        assertThat(retryOptionsForEachQueue).allSatisfy(retryOptions -> {
            assertThat(retryOptions.getMaxRetries()).isZero();
            assertThat(retryOptions.getTryTimeout()).isEqualTo(ofSeconds(10));
        });
    }

    @Test
    void should_start_checking_both_queues_straight_away() {
        verify(queuePeekClient, timeout(5_000).times(2)).peekMessage();
        verify(queueReachabilityLogger, timeout(5_000)).reachable("ng-send-email");
        verify(queueReachabilityLogger, timeout(5_000)).reachable("ng-send-letter");
    }

    @Test
    void should_report_service_bus_health_with_one_component_per_command_queue() {
        final CompositeHealthContributor serviceBusHealth =
                serviceBusHealthConfig.serviceBusHealth(queueReachabilityPoller);

        final List<String> componentNames = serviceBusHealth.stream()
                .map(HealthContributors.Entry::name)
                .toList();
        assertThat(componentNames).containsExactlyInAnyOrder("ng-send-email", "ng-send-letter");
        assertThat(serviceBusHealth.getContributor("ng-send-email")).isInstanceOf(CommandQueueHealthIndicator.class);
        assertThat(serviceBusHealth.getContributor("ng-send-letter")).isInstanceOf(CommandQueueHealthIndicator.class);
    }

    @Test
    void should_count_a_successful_peek_for_two_rounds_of_checks_with_every_peek_timing_out() {
        final CompositeHealthContributor serviceBusHealth =
                serviceBusHealthConfig.serviceBusHealth(queueReachabilityPoller);
        final HealthIndicator emailCommandQueueHealth =
                (HealthIndicator) serviceBusHealth.getContributor("ng-send-email");
        verify(queueReachabilityLogger, timeout(5_000)).reachable("ng-send-email");

        final Duration intervalPlusBothPeeksTimingOut = ofSeconds(30 + 10 + 10);
        final Duration twoRoundsOfChecks = intervalPlusBothPeeksTimingOut.multipliedBy(2);

        when(clock.zonedDateTime()).thenReturn(checkTime.plus(twoRoundsOfChecks).minusSeconds(1));
        assertThat(emailCommandQueueHealth.health().getStatus()).isEqualTo(UP);
        when(clock.zonedDateTime()).thenReturn(checkTime.plus(twoRoundsOfChecks));
        assertThat(emailCommandQueueHealth.health().getStatus()).isEqualTo(DOWN);
    }
}
