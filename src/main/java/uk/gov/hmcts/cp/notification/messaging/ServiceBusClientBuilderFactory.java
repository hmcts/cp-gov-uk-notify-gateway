package uk.gov.hmcts.cp.notification.messaging;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static org.springframework.util.StringUtils.hasText;

/**
 * Creates Service Bus client builders that are already authenticated. Auth switches by config (NG-S07): the
 * connection string is the emulator / local exception (NFR-002); when it is blank, the client authenticates to
 * the namespace with DefaultAzureCredential (Managed Identity in AKS).
 */
@Component
@ConditionalOnServiceBusConfigured
public class ServiceBusClientBuilderFactory {
    private final String serviceBusConnectionString;
    private final String serviceBusNamespace;

    public ServiceBusClientBuilderFactory(
            @Value("${cp.notification.servicebus.connection-string:}") final String serviceBusConnectionString,
            @Value("${cp.notification.servicebus.namespace:}") final String serviceBusNamespace) {
        this.serviceBusConnectionString = serviceBusConnectionString;
        this.serviceBusNamespace = serviceBusNamespace;
    }

    public ServiceBusClientBuilder authenticatedBuilder() {
        final ServiceBusClientBuilder unauthenticatedBuilder = new ServiceBusClientBuilder();
        final ServiceBusClientBuilder authenticatedBuilder;
        if (hasText(serviceBusConnectionString)) {
            authenticatedBuilder = unauthenticatedBuilder.connectionString(serviceBusConnectionString);
        } else {
            authenticatedBuilder = unauthenticatedBuilder
                    .fullyQualifiedNamespace(serviceBusNamespace)
                    .credential(new DefaultAzureCredentialBuilder().build());
        }
        return authenticatedBuilder;
    }
}
