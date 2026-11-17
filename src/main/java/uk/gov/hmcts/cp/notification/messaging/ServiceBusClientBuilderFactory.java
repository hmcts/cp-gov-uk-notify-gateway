package uk.gov.hmcts.cp.notification.messaging;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import org.springframework.stereotype.Component;

import static org.springframework.util.StringUtils.hasText;

/**
 * Creates Service Bus client builders that are already authenticated: by connection string when there is one,
 * otherwise to the namespace with DefaultAzureCredential (Managed Identity in AKS). Every client shares one
 * credential, so they share its cached token.
 */
@Component
@ConditionalOnServiceBusConfigured
public class ServiceBusClientBuilderFactory {
    private final ServiceBusProperties serviceBusProperties;
    private final TokenCredential namespaceCredential;

    public ServiceBusClientBuilderFactory(final ServiceBusProperties serviceBusProperties) {
        this.serviceBusProperties = serviceBusProperties;
        this.namespaceCredential = new DefaultAzureCredentialBuilder().build();
    }

    public ServiceBusClientBuilder authenticatedBuilder() {
        final ServiceBusClientBuilder unauthenticatedBuilder = new ServiceBusClientBuilder();
        final String connectionString = serviceBusProperties.connectionString();
        final ServiceBusClientBuilder authenticatedBuilder;
        if (hasText(connectionString)) {
            authenticatedBuilder = unauthenticatedBuilder.connectionString(connectionString);
        } else {
            authenticatedBuilder = unauthenticatedBuilder
                    .fullyQualifiedNamespace(serviceBusProperties.namespace())
                    .credential(namespaceCredential);
        }
        return authenticatedBuilder;
    }
}
