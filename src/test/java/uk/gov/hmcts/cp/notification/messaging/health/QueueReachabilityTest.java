package uk.gov.hmcts.cp.notification.messaging.health;

import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Unreachable;

import java.time.ZonedDateTime;

import static java.time.ZonedDateTime.parse;
import static org.assertj.core.api.Assertions.assertThat;

class QueueReachabilityTest {
    private final ZonedDateTime checkedAt = parse("2026-11-10T10:00:00Z");

    @Test
    void should_describe_an_unreachable_queue_by_the_failure_message() {
        final Unreachable unreachableQueue =
                new Unreachable(checkedAt, new IllegalStateException("Timeout on blocking read"));

        assertThat(unreachableQueue.peekFailureDescription()).isEqualTo("Timeout on blocking read");
    }

    @Test
    void should_describe_an_unreachable_queue_by_the_failure_type_when_the_failure_has_no_message() {
        final Unreachable unreachableQueue = new Unreachable(checkedAt, new IllegalStateException());

        assertThat(unreachableQueue.peekFailureDescription()).isEqualTo(IllegalStateException.class.getName());
    }
}
