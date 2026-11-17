package uk.gov.hmcts.cp.notification.messaging.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ScheduledExecutorService;

import static java.time.Duration.ofSeconds;
import static java.util.List.of;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QueueReachabilityPollerTest {

    @Mock
    private QueueReachabilityCheck emailCommandQueueReachabilityCheck;

    @Mock
    private QueueReachabilityCheck letterCommandQueueReachabilityCheck;

    @Mock
    private ScheduledExecutorService checkScheduler;

    @Captor
    private ArgumentCaptor<Runnable> scheduledCheck;

    private QueueReachabilityPoller queueReachabilityPoller;

    @BeforeEach
    void createPollerForBothCommandQueues() {
        queueReachabilityPoller = new QueueReachabilityPoller(
                of(emailCommandQueueReachabilityCheck, letterCommandQueueReachabilityCheck),
                checkScheduler,
                ofSeconds(30));
    }

    @Test
    void should_check_every_queue_straight_away_and_then_every_interval_after_the_last_check_ends() {
        queueReachabilityPoller.start();

        verify(checkScheduler).scheduleWithFixedDelay(scheduledCheck.capture(), eq(0L), eq(30_000L), eq(MILLISECONDS));
        final Runnable checkEveryQueue = scheduledCheck.getValue();
        checkEveryQueue.run();
        verify(emailCommandQueueReachabilityCheck).checkReachability();
        verify(letterCommandQueueReachabilityCheck).checkReachability();
    }

    @Test
    void should_give_the_checks_it_runs() {
        assertThat(queueReachabilityPoller.getQueueReachabilityChecks())
                .containsExactly(emailCommandQueueReachabilityCheck, letterCommandQueueReachabilityCheck);
    }

    @Test
    void should_let_a_running_check_finish_before_closing_each_check() {
        queueReachabilityPoller.close();

        final InOrder closeOrder =
                inOrder(checkScheduler, emailCommandQueueReachabilityCheck, letterCommandQueueReachabilityCheck);
        closeOrder.verify(checkScheduler).close();
        closeOrder.verify(emailCommandQueueReachabilityCheck).close();
        closeOrder.verify(letterCommandQueueReachabilityCheck).close();
    }
}
