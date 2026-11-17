package uk.gov.hmcts.cp.notification.messaging;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder.ServiceBusProcessorClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.messaging.ServiceBusProperties.HealthCheck;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceBusConfigTest {
    private final ServiceBusConfig serviceBusConfig = new ServiceBusConfig();
    private final ServiceBusProperties serviceBusProperties = new ServiceBusProperties(
            "", "my-namespace.servicebus.windows.net", "ng-send-email", "ng-send-letter", 15,
            new HealthCheck(ofSeconds(30), ofSeconds(10)));
    private final ServiceBusProcessorClientBuilder processorClientBuilder =
            mock(ServiceBusProcessorClientBuilder.class, RETURNS_SELF);

    @Mock
    private ServiceBusClientBuilderFactory serviceBusClientBuilderFactory;

    @Mock
    private ServiceBusClientBuilder serviceBusClientBuilder;

    @Mock
    private ServiceBusProcessorClient builtProcessorClient;

    @Mock
    private SendEmailConsumer sendEmailConsumer;

    @Mock
    private SendLetterConsumer sendLetterConsumer;

    @BeforeEach
    void buildProcessorsThroughTheAuthenticatedBuilder() {
        when(serviceBusClientBuilderFactory.authenticatedBuilder()).thenReturn(serviceBusClientBuilder);
        when(serviceBusClientBuilder.processor()).thenReturn(processorClientBuilder);
        when(processorClientBuilder.buildProcessorClient()).thenReturn(builtProcessorClient);
    }

    @Test
    void should_start_a_processor_on_the_email_command_queue() {
        final ServiceBusProcessorClient sendEmailProcessorClient = serviceBusConfig.sendEmailProcessorClient(
                serviceBusClientBuilderFactory, serviceBusProperties, sendEmailConsumer);

        assertThat(sendEmailProcessorClient).isSameAs(builtProcessorClient);
        verify(processorClientBuilder).queueName("ng-send-email");
        verifyProcessorSettingsAndStart();
    }

    @Test
    void should_start_a_processor_on_the_letter_command_queue() {
        final ServiceBusProcessorClient sendLetterProcessorClient = serviceBusConfig.sendLetterProcessorClient(
                serviceBusClientBuilderFactory, serviceBusProperties, sendLetterConsumer);

        assertThat(sendLetterProcessorClient).isSameAs(builtProcessorClient);
        verify(processorClientBuilder).queueName("ng-send-letter");
        verifyProcessorSettingsAndStart();
    }

    private void verifyProcessorSettingsAndStart() {
        verify(processorClientBuilder).maxConcurrentCalls(15);
        verify(processorClientBuilder).disableAutoComplete();
        verify(processorClientBuilder).processMessage(any());
        verify(processorClientBuilder).processError(any());
        verify(builtProcessorClient).start();
    }
}
