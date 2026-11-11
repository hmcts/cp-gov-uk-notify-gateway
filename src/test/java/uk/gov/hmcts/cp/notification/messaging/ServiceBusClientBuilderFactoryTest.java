package uk.gov.hmcts.cp.notification.messaging;

import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceBusClientBuilderFactoryTest {
    private final String queueName = "ng-send-email";

    @Test
    void should_address_the_namespace_in_the_connection_string_when_one_is_configured() {
        final ServiceBusClientBuilderFactory serviceBusClientBuilderFactory = new ServiceBusClientBuilderFactory(
                "Endpoint=sb://from-connection-string.servicebus.windows.net/;SharedAccessKeyName=test;"
                        + "SharedAccessKey=dGVzdA==",
                "from-namespace.servicebus.windows.net");

        try (ServiceBusReceiverClient receiverClient = receiverClientFrom(serviceBusClientBuilderFactory)) {
            assertThat(receiverClient.getFullyQualifiedNamespace())
                    .isEqualTo("from-connection-string.servicebus.windows.net");
            assertThat(receiverClient.getEntityPath()).isEqualTo(queueName);
        }
    }

    @Test
    void should_address_the_configured_namespace_when_there_is_no_connection_string() {
        final ServiceBusClientBuilderFactory serviceBusClientBuilderFactory =
                new ServiceBusClientBuilderFactory("", "from-namespace.servicebus.windows.net");

        try (ServiceBusReceiverClient receiverClient = receiverClientFrom(serviceBusClientBuilderFactory)) {
            assertThat(receiverClient.getFullyQualifiedNamespace()).isEqualTo("from-namespace.servicebus.windows.net");
            assertThat(receiverClient.getEntityPath()).isEqualTo(queueName);
        }
    }

    private ServiceBusReceiverClient receiverClientFrom(
            final ServiceBusClientBuilderFactory serviceBusClientBuilderFactory) {
        return serviceBusClientBuilderFactory.authenticatedBuilder().receiver().queueName(queueName).buildClient();
    }
}
