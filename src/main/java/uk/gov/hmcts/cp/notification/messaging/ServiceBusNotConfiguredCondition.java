package uk.gov.hmcts.cp.notification.messaging;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class ServiceBusNotConfiguredCondition implements Condition {
    private final ServiceBusConfiguredCondition serviceBusConfiguredCondition = new ServiceBusConfiguredCondition();

    @Override
    public boolean matches(final ConditionContext conditionContext, final AnnotatedTypeMetadata annotatedTypeMetadata) {
        return !serviceBusConfiguredCondition.matches(conditionContext, annotatedTypeMetadata);
    }
}
