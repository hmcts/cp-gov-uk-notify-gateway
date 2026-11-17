package uk.gov.hmcts.cp.notification.messaging;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import static org.springframework.util.StringUtils.hasText;

/**
 * Service Bus is configured when it has a connection string (emulator / local) or a namespace (Managed Identity).
 */
public class ServiceBusConfiguredCondition implements Condition {

    @Override
    public boolean matches(final ConditionContext conditionContext, final AnnotatedTypeMetadata annotatedTypeMetadata) {
        final Environment environment = conditionContext.getEnvironment();
        final String connectionString = environment.getProperty("cp.notification.servicebus.connection-string", "");
        final String namespace = environment.getProperty("cp.notification.servicebus.namespace", "");
        return hasText(connectionString) || hasText(namespace);
    }
}
