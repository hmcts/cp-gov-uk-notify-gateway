package uk.gov.hmcts.cp.notification.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceBusConfiguredConditionTest {
    private final ServiceBusConfiguredCondition serviceBusConfiguredCondition = new ServiceBusConfiguredCondition();
    private final ServiceBusNotConfiguredCondition serviceBusNotConfiguredCondition =
            new ServiceBusNotConfiguredCondition();
    private final MockEnvironment environment = new MockEnvironment();

    @Mock
    private ConditionContext conditionContext;

    @Mock
    private AnnotatedTypeMetadata annotatedTypeMetadata;

    @ParameterizedTest
    @CsvSource({
        "Endpoint=sb://emulator/, ''",
        "'', my-namespace.servicebus.windows.net",
        "Endpoint=sb://emulator/, my-namespace.servicebus.windows.net"
    })
    void should_count_service_bus_as_configured_with_a_connection_string_or_a_namespace(
            final String connectionString, final String namespace) {
        environment.setProperty("cp.notification.servicebus.connection-string", connectionString);
        environment.setProperty("cp.notification.servicebus.namespace", namespace);
        when(conditionContext.getEnvironment()).thenReturn(environment);

        assertThat(serviceBusConfiguredCondition.matches(conditionContext, annotatedTypeMetadata)).isTrue();
        assertThat(serviceBusNotConfiguredCondition.matches(conditionContext, annotatedTypeMetadata)).isFalse();
    }

    @Test
    void should_count_service_bus_as_not_configured_when_both_are_blank() {
        environment.setProperty("cp.notification.servicebus.connection-string", " ");
        environment.setProperty("cp.notification.servicebus.namespace", "");
        when(conditionContext.getEnvironment()).thenReturn(environment);

        assertThat(serviceBusConfiguredCondition.matches(conditionContext, annotatedTypeMetadata)).isFalse();
        assertThat(serviceBusNotConfiguredCondition.matches(conditionContext, annotatedTypeMetadata)).isTrue();
    }

    @Test
    void should_count_service_bus_as_not_configured_when_neither_is_set() {
        when(conditionContext.getEnvironment()).thenReturn(environment);

        assertThat(serviceBusConfiguredCondition.matches(conditionContext, annotatedTypeMetadata)).isFalse();
        assertThat(serviceBusNotConfiguredCondition.matches(conditionContext, annotatedTypeMetadata)).isTrue();
    }
}
