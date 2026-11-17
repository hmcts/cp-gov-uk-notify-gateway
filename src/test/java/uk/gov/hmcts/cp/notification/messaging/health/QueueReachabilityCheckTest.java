package uk.gov.hmcts.cp.notification.messaging.health;

import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.logging.QueueReachabilityLogger;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.NotYetChecked;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Reachable;
import uk.gov.hmcts.cp.notification.messaging.health.QueueReachability.Unreachable;
import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.ZonedDateTime;

import static com.azure.messaging.servicebus.ServiceBusErrorSource.RECEIVE;
import static java.time.ZonedDateTime.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueReachabilityCheckTest {
    private final ZonedDateTime firstCheckTime = parse("2026-11-10T10:00:00Z");
    private final ZonedDateTime secondCheckTime = firstCheckTime.plusSeconds(30);
    private final ServiceBusException peekFailure =
            new ServiceBusException(new IllegalStateException("connection refused"), RECEIVE);

    @Mock
    private ServiceBusReceiverClient queuePeekClient;

    @Mock
    private QueueReachabilityLogger queueReachabilityLogger;

    @Mock
    private Clock clock;

    private QueueReachabilityCheck queueReachabilityCheck;

    @BeforeEach
    void createReachabilityCheckForTheEmailCommandQueue() {
        queueReachabilityCheck =
                new QueueReachabilityCheck("ng-send-email", queuePeekClient, queueReachabilityLogger, clock);
    }

    @Test
    void should_be_named_after_its_queue() {
        assertThat(queueReachabilityCheck.getQueueName()).isEqualTo("ng-send-email");
    }

    @Test
    void should_not_have_checked_the_queue_before_it_is_asked_to() {
        assertThat(queueReachabilityCheck.getLastCheck()).isEqualTo(new NotYetChecked());
        verifyNoInteractions(queuePeekClient, queueReachabilityLogger);
    }

    @Test
    void should_count_a_peek_that_runs_out_of_time_as_unreachable() {
        final IllegalStateException peekTimeout = new IllegalStateException("Timeout on blocking read");
        doThrow(peekTimeout).when(queuePeekClient).peekMessage();

        queueReachabilityCheck.checkReachability();

        assertThat(queueReachabilityCheck.getLastCheck()).isEqualTo(new Unreachable());
        verify(queueReachabilityLogger).unreachable("ng-send-email", peekTimeout);
    }

    @Test
    void should_close_its_peek_client_when_closed() {
        queueReachabilityCheck.close();

        verify(queuePeekClient).close();
    }

    @Nested
    class WhenThePeekSucceeds {

        @BeforeEach
        void peekTheQueueSuccessfully() {
            when(clock.zonedDateTime()).thenReturn(firstCheckTime);
            queueReachabilityCheck.checkReachability();
        }

        @Test
        void should_remember_when_the_queue_was_reached_and_log_that_it_can_be() {
            verify(queuePeekClient).peekMessage();
            assertThat(queueReachabilityCheck.getLastCheck()).isEqualTo(new Reachable(firstCheckTime));
            verify(queueReachabilityLogger).reachable("ng-send-email");
        }

        @Test
        void should_remember_the_latest_success_without_logging_again() {
            when(clock.zonedDateTime()).thenReturn(secondCheckTime);

            queueReachabilityCheck.checkReachability();

            assertThat(queueReachabilityCheck.getLastCheck()).isEqualTo(new Reachable(secondCheckTime));
            verify(queueReachabilityLogger).reachable("ng-send-email");
            verifyNoMoreInteractions(queueReachabilityLogger);
        }

        @Test
        void should_warn_once_the_queue_can_no_longer_be_reached() {
            doThrow(peekFailure).when(queuePeekClient).peekMessage();

            queueReachabilityCheck.checkReachability();

            assertThat(queueReachabilityCheck.getLastCheck()).isEqualTo(new Unreachable());
            verify(queueReachabilityLogger).unreachable("ng-send-email", peekFailure);
        }
    }

    @Nested
    class WhenThePeekFails {

        @BeforeEach
        void failToPeekTheQueue() {
            doThrow(peekFailure).when(queuePeekClient).peekMessage();
            queueReachabilityCheck.checkReachability();
        }

        @Test
        void should_remember_that_the_queue_cannot_be_reached_and_warn() {
            assertThat(queueReachabilityCheck.getLastCheck()).isEqualTo(new Unreachable());
            verify(queueReachabilityLogger).unreachable("ng-send-email", peekFailure);
        }

        @Test
        void should_not_warn_again_while_the_queue_stays_unreachable() {
            queueReachabilityCheck.checkReachability();

            verify(queueReachabilityLogger).unreachable("ng-send-email", peekFailure);
            verifyNoMoreInteractions(queueReachabilityLogger);
        }

        @Test
        void should_log_once_the_queue_can_be_reached_again() {
            doReturn(null).when(queuePeekClient).peekMessage();
            when(clock.zonedDateTime()).thenReturn(secondCheckTime);

            queueReachabilityCheck.checkReachability();

            assertThat(queueReachabilityCheck.getLastCheck()).isEqualTo(new Reachable(secondCheckTime));
            verify(queueReachabilityLogger).reachable("ng-send-email");
        }
    }
}
