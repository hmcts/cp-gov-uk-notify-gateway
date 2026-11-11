package uk.gov.hmcts.cp.notification.messaging.health;

import org.junit.jupiter.api.Test;

import static java.time.Duration.ZERO;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ServiceBusHealthCheckPropertiesTest {

    @Test
    void should_accept_a_stale_after_longer_than_the_interval_plus_the_timeout() {
        final ServiceBusHealthCheckProperties healthCheckProperties =
                new ServiceBusHealthCheckProperties(ofSeconds(30), ofSeconds(10), ofSeconds(41));

        assertThat(healthCheckProperties.staleAfter()).isEqualTo(ofSeconds(41));
    }

    @Test
    void should_refuse_a_stale_after_no_longer_than_the_interval_plus_the_timeout() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServiceBusHealthCheckProperties(ofSeconds(30), ofSeconds(10), ofSeconds(40)))
                .withMessage("The Service Bus health check stale-after (PT40S) must be longer than the interval "
                        + "(PT30S) plus the timeout (PT10S), or readiness would fail between checks while the queues "
                        + "are fine. Check CP_NG_SERVICEBUS_HEALTH_CHECK_STALE_AFTER.");
    }

    @Test
    void should_refuse_an_interval_that_is_not_longer_than_zero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServiceBusHealthCheckProperties(ZERO, ofSeconds(10), ofSeconds(90)))
                .withMessage("The Service Bus health check interval (PT0S) and timeout (PT10S) must both be longer "
                        + "than zero. Check CP_NG_SERVICEBUS_HEALTH_CHECK_INTERVAL and "
                        + "CP_NG_SERVICEBUS_HEALTH_CHECK_TIMEOUT.");
    }

    @Test
    void should_refuse_a_timeout_that_is_not_longer_than_zero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServiceBusHealthCheckProperties(ofSeconds(30), ofSeconds(-1), ofSeconds(90)))
                .withMessageStartingWith("The Service Bus health check interval (PT30S) and timeout (PT-1S)");
    }
}
