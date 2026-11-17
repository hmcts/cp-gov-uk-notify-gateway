package uk.gov.hmcts.cp.notification.messaging;

import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.notification.messaging.ServiceBusProperties.HealthCheck;

import static java.time.Duration.ZERO;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ServiceBusPropertiesTest {

    @Test
    void should_accept_a_health_check_interval_and_timeout_longer_than_zero() {
        final HealthCheck healthCheck = new HealthCheck(ofSeconds(30), ofSeconds(10));

        assertThat(healthCheck.interval()).isEqualTo(ofSeconds(30));
        assertThat(healthCheck.timeout()).isEqualTo(ofSeconds(10));
    }

    @Test
    void should_refuse_a_health_check_interval_that_is_not_longer_than_zero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HealthCheck(ZERO, ofSeconds(10)))
                .withMessage("""
                        The Service Bus health check interval (PT0S) and timeout (PT10S) \
                        must both be longer than zero. \
                        Check CP_NG_SERVICEBUS_HEALTH_CHECK_INTERVAL \
                        and CP_NG_SERVICEBUS_HEALTH_CHECK_TIMEOUT.""");
    }

    @Test
    void should_refuse_a_health_check_timeout_that_is_not_longer_than_zero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HealthCheck(ofSeconds(30), ofSeconds(-1)))
                .withMessageStartingWith("The Service Bus health check interval (PT30S) and timeout (PT-1S)");
    }

    @Test
    void should_leave_the_connection_string_out_of_its_text() {
        final ServiceBusProperties serviceBusProperties = new ServiceBusProperties(
                "Endpoint=sb://emulator/;SharedAccessKeyName=test;SharedAccessKey=dGVzdA==",
                "",
                "ng-send-email",
                "ng-send-letter",
                15,
                new HealthCheck(ofSeconds(30), ofSeconds(10)));

        assertThat(serviceBusProperties.toString()).isEqualTo("""
                ServiceBusProperties[\
                connectionString=<redacted>, \
                namespace=, \
                commandQueue=ng-send-email, \
                letterCommandQueue=ng-send-letter, \
                maxConcurrentCalls=15, \
                healthCheck=HealthCheck[interval=PT30S, timeout=PT10S]]""");
    }
}
