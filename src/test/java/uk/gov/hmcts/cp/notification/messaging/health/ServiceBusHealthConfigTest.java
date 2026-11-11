package uk.gov.hmcts.cp.notification.messaging.health;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder.ServiceBusReceiverClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import uk.gov.hmcts.cp.notification.messaging.ServiceBusClientBuilderFactory;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.NotYetChecked;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.ZonedDateTime;

import static java.time.Duration.ofSeconds;
import static java.time.ZonedDateTime.parse;
import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.boot.health.contributor.Status.DOWN;

@ExtendWith(MockitoExtension.class)
class ServiceBusHealthConfigTest {
    private final ServiceBusHealthCheckProperties healthCheckProperties = new ServiceBusHealthCheckProperties(
            ofSeconds(30), ofSeconds(10), ofSeconds(90));
    private final ZonedDateTime checkTime = parse("2026-11-10T10:00:00Z");

    @Mock
    private ServiceBusClientBuilderFactory serviceBusClientBuilderFactory;

    @Mock
    private Clock clock;

    private ServiceBusHealthConfig serviceBusHealthConfig;

    @BeforeEach
    void createServiceBusHealthConfig() {
        serviceBusHealthConfig = new ServiceBusHealthConfig(serviceBusClientBuilderFactory, healthCheckProperties, clock);
    }

    @Nested
    class QueueReachabilityChecks {
        private final ServiceBusClientBuilder serviceBusClientBuilder =
                mock(ServiceBusClientBuilder.class, RETURNS_SELF);
        private final ServiceBusReceiverClientBuilder receiverClientBuilder =
                mock(ServiceBusReceiverClientBuilder.class, RETURNS_SELF);

        @Mock
        private ServiceBusReceiverClient queuePeekClient;

        @Mock
        private Logger queueReachabilityCheckLogger;

        @Captor
        private ArgumentCaptor<AmqpRetryOptions> peekRetryOptions;

        @BeforeEach
        void buildPeekClientsThroughTheAuthenticatedBuilder() {
            when(serviceBusClientBuilderFactory.authenticatedBuilder()).thenReturn(serviceBusClientBuilder);
            when(serviceBusClientBuilder.receiver()).thenReturn(receiverClientBuilder);
            when(receiverClientBuilder.buildClient()).thenReturn(queuePeekClient);
        }

        @Test
        void should_peek_the_email_command_queue_once_with_the_configured_timeout() {
            final QueueReachabilityCheck emailCommandQueueReachabilityCheck = serviceBusHealthConfig
                    .emailCommandQueueReachabilityCheck("ng-send-email", queueReachabilityCheckLogger);

            verify(receiverClientBuilder).queueName("ng-send-email");
            verifyOneAttemptWithTheConfiguredTimeout();
            emailCommandQueueReachabilityCheck.checkAt(checkTime);
            verify(queuePeekClient).peekMessage();
            verify(queueReachabilityCheckLogger).info(any(String.class), eq("ng-send-email"));
        }

        @Test
        void should_peek_the_letter_command_queue_once_with_the_configured_timeout() {
            final QueueReachabilityCheck letterCommandQueueReachabilityCheck = serviceBusHealthConfig
                    .letterCommandQueueReachabilityCheck("ng-send-letter", queueReachabilityCheckLogger);

            verify(receiverClientBuilder).queueName("ng-send-letter");
            verifyOneAttemptWithTheConfiguredTimeout();
            letterCommandQueueReachabilityCheck.checkAt(checkTime);
            verify(queuePeekClient).peekMessage();
            verify(queueReachabilityCheckLogger).info(any(String.class), eq("ng-send-letter"));
        }

        private void verifyOneAttemptWithTheConfiguredTimeout() {
            verify(serviceBusClientBuilder).retryOptions(peekRetryOptions.capture());
            assertThat(peekRetryOptions.getValue().getMaxRetries()).isZero();
            assertThat(peekRetryOptions.getValue().getTryTimeout()).isEqualTo(ofSeconds(10));
        }
    }

    @Test
    void should_poll_every_reachability_check_at_the_current_time() {
        when(clock.zonedDateTime()).thenReturn(checkTime);
        final QueueReachabilityCheck emailCommandQueueReachabilityCheck = mock(QueueReachabilityCheck.class);
        final QueueReachabilityCheck letterCommandQueueReachabilityCheck = mock(QueueReachabilityCheck.class);

        serviceBusHealthConfig
                .queueReachabilityPoller(of(emailCommandQueueReachabilityCheck, letterCommandQueueReachabilityCheck))
                .checkEveryQueue();

        verify(emailCommandQueueReachabilityCheck).checkAt(checkTime);
        verify(letterCommandQueueReachabilityCheck).checkAt(checkTime);
    }

    @Test
    void should_report_each_command_queue_from_its_own_processor_and_reachability_check() {
        final ServiceBusProcessorClient sendEmailProcessorClient = mock(ServiceBusProcessorClient.class);
        final ServiceBusProcessorClient sendLetterProcessorClient = mock(ServiceBusProcessorClient.class);
        final QueueReachabilityCheck letterCommandQueueReachabilityCheck = mock(QueueReachabilityCheck.class);
        when(sendEmailProcessorClient.isRunning()).thenReturn(false);
        when(sendLetterProcessorClient.isRunning()).thenReturn(true);
        when(letterCommandQueueReachabilityCheck.lastOutcome()).thenReturn(new NotYetChecked());

        final CompositeHealthContributor serviceBusHealthContributor = serviceBusHealthConfig.serviceBusHealthContributor(
                "ng-send-email", "ng-send-letter", sendEmailProcessorClient, sendLetterProcessorClient,
                mock(QueueReachabilityCheck.class), letterCommandQueueReachabilityCheck);

        final Health emailCommandQueueHealth =
                ((HealthIndicator) serviceBusHealthContributor.getContributor("ng-send-email")).health();
        final Health letterCommandQueueHealth =
                ((HealthIndicator) serviceBusHealthContributor.getContributor("ng-send-letter")).health();
        assertThat(emailCommandQueueHealth.getStatus()).isEqualTo(DOWN);
        assertThat(emailCommandQueueHealth.getDetails()).containsEntry("reason", "The queue's processor is not running");
        assertThat(letterCommandQueueHealth.getStatus()).isEqualTo(DOWN);
        assertThat(letterCommandQueueHealth.getDetails())
                .containsEntry("reason", "The queue has not been checked yet");
    }
}
