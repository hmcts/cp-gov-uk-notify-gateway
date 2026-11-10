package uk.gov.hmcts.cp.notification.messaging.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.time.Clock;

import java.time.ZonedDateTime;

import static java.time.ZonedDateTime.parse;
import static java.util.List.of;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueReachabilityPollerTest {

    @Mock
    private QueueReachabilityCheck emailCommandQueueReachabilityCheck;

    @Mock
    private QueueReachabilityCheck letterCommandQueueReachabilityCheck;

    @Mock
    private Clock clock;

    @Test
    void should_check_every_queue_at_the_current_time() {
        final ZonedDateTime currentTime = parse("2026-11-10T10:00:00Z");
        when(clock.zonedDateTime()).thenReturn(currentTime);
        final QueueReachabilityPoller queueReachabilityPoller = new QueueReachabilityPoller(
                of(emailCommandQueueReachabilityCheck, letterCommandQueueReachabilityCheck), clock);

        queueReachabilityPoller.checkEveryQueue();

        verify(emailCommandQueueReachabilityCheck).checkAt(currentTime);
        verify(letterCommandQueueReachabilityCheck).checkAt(currentTime);
    }
}
