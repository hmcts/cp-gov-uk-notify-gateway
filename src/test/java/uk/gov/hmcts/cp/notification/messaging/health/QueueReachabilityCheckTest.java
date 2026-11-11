package uk.gov.hmcts.cp.notification.messaging.health;

import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.NotYetChecked;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Reachable;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Unreachable;

import java.time.ZonedDateTime;

import static java.time.ZonedDateTime.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class QueueReachabilityCheckTest {
    private static final String REACHABLE_MESSAGE = "Service Bus queue {} can be reached. This instance can read "
            + "notification requests from it, so this queue no longer stops it reporting itself ready.";
    private static final String UNREACHABLE_MESSAGE = "Service Bus queue {} cannot be reached: {}. This instance "
            + "reports itself not ready until the queue can be reached again. Check that the queue exists in the "
            + "Service Bus namespace and that this service is allowed to read from it.";

    private final String queueName = "ng-send-email";
    private final ZonedDateTime firstCheckTime = parse("2026-11-10T10:00:00Z");
    private final ZonedDateTime secondCheckTime = firstCheckTime.plusSeconds(30);
    private final ServiceBusException peekFailure =
            new ServiceBusException(new IllegalStateException("connection refused"), ServiceBusErrorSource.RECEIVE);

    @Mock
    private ServiceBusReceiverClient queuePeekClient;

    @Mock
    private Logger logger;

    private QueueReachabilityCheck queueReachabilityCheck;

    @BeforeEach
    void createQueueReachabilityCheck() {
        queueReachabilityCheck = new QueueReachabilityCheck(queueName, queuePeekClient, logger);
    }

    @Test
    void should_report_not_yet_checked_before_the_first_check() {
        assertThat(queueReachabilityCheck.lastOutcome()).isEqualTo(new NotYetChecked());
    }

    @Nested
    class WhenThePeekSucceeds {

        @Test
        void should_report_the_queue_reachable_and_say_so_once() {
            queueReachabilityCheck.checkAt(firstCheckTime);
            queueReachabilityCheck.checkAt(secondCheckTime);

            verify(queuePeekClient, times(2)).peekMessage();
            assertThat(queueReachabilityCheck.lastOutcome()).isEqualTo(new Reachable(secondCheckTime));
            verify(logger).info(REACHABLE_MESSAGE, queueName);
            verifyNoMoreInteractions(logger);
        }
    }

    @Nested
    class WhenThePeekFails {

        @BeforeEach
        void failEveryPeek() {
            doThrow(peekFailure).when(queuePeekClient).peekMessage();
        }

        @Test
        void should_report_the_queue_unreachable_with_the_error_and_warn_once() {
            queueReachabilityCheck.checkAt(firstCheckTime);
            queueReachabilityCheck.checkAt(secondCheckTime);

            assertThat(queueReachabilityCheck.lastOutcome()).isEqualTo(new Unreachable(secondCheckTime, peekFailure));
            verify(logger).warn(UNREACHABLE_MESSAGE, queueName, peekFailure.getMessage());
            verifyNoMoreInteractions(logger);
        }
    }

    @Test
    void should_report_the_queue_unreachable_and_warn_when_the_peek_runs_out_of_time() {
        final IllegalStateException peekTimeout =
                new IllegalStateException("Timeout on blocking read for 10000000000 NANOSECONDS");
        doThrow(peekTimeout).when(queuePeekClient).peekMessage();

        queueReachabilityCheck.checkAt(firstCheckTime);

        assertThat(queueReachabilityCheck.lastOutcome()).isEqualTo(new Unreachable(firstCheckTime, peekTimeout));
        verify(logger).warn(UNREACHABLE_MESSAGE, queueName, peekTimeout.getMessage());
        verifyNoMoreInteractions(logger);
    }

    @Test
    void should_say_the_queue_can_be_reached_again_when_it_recovers() {
        doThrow(peekFailure).doReturn(null).when(queuePeekClient).peekMessage();

        queueReachabilityCheck.checkAt(firstCheckTime);
        queueReachabilityCheck.checkAt(secondCheckTime);

        assertThat(queueReachabilityCheck.lastOutcome()).isEqualTo(new Reachable(secondCheckTime));
        verify(logger).warn(UNREACHABLE_MESSAGE, queueName, peekFailure.getMessage());
        verify(logger).info(REACHABLE_MESSAGE, queueName);
    }

    @Test
    void should_close_the_peek_client_when_closed() {
        queueReachabilityCheck.close();

        verify(queuePeekClient).close();
    }
}
