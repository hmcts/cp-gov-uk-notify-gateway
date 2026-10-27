package uk.gov.hmcts.cp.notification.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import uk.gov.hmcts.cp.notification.sender.GovNotifyClient;
import uk.gov.hmcts.cp.notification.sender.NotificationStatus;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.LETTER_URL;

@ExtendWith(MockitoExtension.class)
class CheckAcceptedLetterStatusTaskTest {
    private static final List<Long> LEGACY_ACCEPTED_LETTER_RETRY_DURATIONS = Collections.nCopies(16, 43_200L);
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final String REFERENCE = "notify-letter-ref";

    @Mock
    private GovNotifyClient govNotifyClient;
    @Mock
    private NotificationStatusService statusService;
    @Mock
    private ExecutionService executionService;

    private final UUID notificationId = UUID.randomUUID();
    private final CpTaskFactory taskFactory = new CpTaskFactory(JsonMapper.builder().build(), new Clock());

    private CheckAcceptedLetterStatusTask task;

    @BeforeEach
    void createTaskWithLegacyAcceptedLetterRetryDurations() {
        final TransientFailurePolicy failurePolicy = new TransientFailurePolicy(
                statusService, new Clock(java.time.Clock.fixed(NOW, ZoneOffset.UTC)), List.of(1L));
        task = new CheckAcceptedLetterStatusTask(
                new LetterStatusPoller(govNotifyClient, statusService, failurePolicy, executionService, taskFactory),
                LEGACY_ACCEPTED_LETTER_RETRY_DURATIONS);
    }

    @Test
    void should_preserve_the_legacy_accepted_letter_retry_durations_of_twelve_hours_sixteen_times() {
        assertThat(task.getRetryDurationsInSecs()).hasValue(LEGACY_ACCEPTED_LETTER_RETRY_DURATIONS);
    }

    @Test
    void should_poll_again_while_the_letter_is_still_accepted() {
        when(govNotifyClient.checkStatus(REFERENCE)).thenReturn(NotificationStatus.ACCEPTED);

        final ExecutionInfo result = task.execute(acceptedLetterJob());

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
        verifyNoInteractions(statusService, executionService);
    }

    @Test
    void should_schedule_the_final_poll_twelve_hours_later() {
        when(govNotifyClient.checkStatus(REFERENCE)).thenReturn(NotificationStatus.ACCEPTED);

        final ExecutionInfo result = task.execute(withRetriesRemaining(1));

        assertThat(result.isShouldRetry()).isFalse();
        assertThat(result.getAssignedTaskStartTime())
                .isEqualTo(ZonedDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(43_200));
    }

    @Test
    void should_mark_failed_when_the_letter_is_still_accepted_after_every_retry() {
        when(govNotifyClient.checkStatus(REFERENCE)).thenReturn(NotificationStatus.ACCEPTED);

        final ExecutionInfo result = task.execute(withRetriesRemaining(0));

        verify(statusService).markFailed(eq(notificationId), isNull(),
                eq("Gov.Notify did not reach a terminal status within the retry window (last status 'accepted')"));
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void should_mark_the_letter_sent_once_gov_notify_has_received_it() {
        when(govNotifyClient.checkStatus(REFERENCE)).thenReturn(NotificationStatus.RECEIVED);

        final ExecutionInfo result = task.execute(acceptedLetterJob());

        verify(statusService).markLetterSent(notificationId);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    private ExecutionInfo acceptedLetterJob() {
        return taskFactory.createCheckAcceptedLetterStatusJob(
                new LetterJob(notificationId, LETTER_URL, false, 5), REFERENCE);
    }

    private ExecutionInfo withRetriesRemaining(final int retriesRemaining) {
        return ExecutionInfo.executionInfo().from(acceptedLetterJob()).withRetryAttemptsRemaining(retriesRemaining).build();
    }
}
