package uk.gov.hmcts.cp.notification.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import uk.gov.hmcts.cp.notification.sender.GovNotifyClient;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.NotificationStatus;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLHandshakeException;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.TransientFailurePolicyFactory.aFailedNotificationRecorder;
import static uk.gov.hmcts.cp.notification.integration.testdata.TransientFailurePolicyFactory.aStatusCheckFailureHandler;
import static uk.gov.hmcts.cp.notification.integration.testdata.TransientFailurePolicyFactory.aTransientFailurePolicy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.LETTER_URL;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.DELIVERY_FAILED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_FAILED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_UNREACHABLE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.STATUS_UNKNOWN;

@ExtendWith(MockitoExtension.class)
class CheckLetterStatusTaskTest {
    private static final List<Long> LEGACY_LETTER_STATUS_RETRY_DURATIONS =
            List.of(60L, 300L, 1800L, 3600L, 7200L, 14400L);
    private static final String REFERENCE = "notify-letter-ref";

    @Mock
    private GovNotifyClient govNotifyClient;
    @Mock
    private NotificationStatusService statusService;
    @Mock
    private ExecutionService executionService;

    private final UUID notificationId = UUID.randomUUID();
    private final CpTaskFactory taskFactory = new CpTaskFactory(JsonMapper.builder().build(), new Clock());

    private CheckLetterStatusTask task;

    @BeforeEach
    void createTaskWithLegacyLetterStatusRetryDurations() {
        final TaskRetrySchedule taskRetrySchedule = new TaskRetrySchedule(new Clock());
        final TransientFailurePolicy failurePolicy =
                aTransientFailurePolicy(statusService, taskRetrySchedule);
        final LetterStatusPoller poller = new LetterStatusPoller(govNotifyClient, statusService, failurePolicy,
                aFailedNotificationRecorder(statusService, taskRetrySchedule),
                aStatusCheckFailureHandler(statusService, taskRetrySchedule),
                taskRetrySchedule, executionService, taskFactory);
        task = new CheckLetterStatusTask(
                poller, executionService, taskFactory, taskRetrySchedule, LEGACY_LETTER_STATUS_RETRY_DURATIONS);
    }

    @Test
    void should_preserve_the_legacy_letter_status_retry_durations() {
        assertThat(task.getRetryDurationsInSecs()).hasValue(LEGACY_LETTER_STATUS_RETRY_DURATIONS);
    }

    @Nested
    class WhenGovNotifyHasReceivedTheLetter {

        @Test
        void should_mark_the_letter_sent_and_complete() {
            when(govNotifyClient.checkStatus(REFERENCE)).thenReturn(NotificationStatus.RECEIVED);

            final ExecutionInfo result = task.execute(checkLetterStatusJob(5));

            verify(statusService).markLetterSent(notificationId);
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            verifyNoInteractions(executionService);
        }
    }

    @Nested
    class WhenGovNotifyHasAcceptedTheLetter {

        @Test
        void should_hand_the_letter_to_the_accepted_letter_status_check_and_complete() {
            when(govNotifyClient.checkStatus(REFERENCE)).thenReturn(NotificationStatus.ACCEPTED);

            final ExecutionInfo result = task.execute(checkLetterStatusJob(4));

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            final ArgumentCaptor<ExecutionInfo> next = ArgumentCaptor.forClass(ExecutionInfo.class);
            verify(executionService).executeWith(next.capture());
            assertThat(next.getValue().getAssignedTaskName()).isEqualTo(CheckAcceptedLetterStatusTask.TASK_NAME);
            assertThat(next.getValue().getJobData().getString(CheckLetterStatusTask.KEY_REFERENCE)).isEqualTo(REFERENCE);
            assertThat(LetterJob.from(next.getValue().getJobData()))
                    .isEqualTo(new LetterJob(notificationId, LETTER_URL, true, 4));
            verifyNoInteractions(statusService);
        }
    }

    @Nested
    class WhenGovNotifyFailsTheLettersValidation {

        @Test
        void should_requeue_the_letter_and_send_it_again_with_one_fewer_resend() {
            when(govNotifyClient.checkStatus(REFERENCE)).thenReturn(NotificationStatus.INVALID_REQUEST);

            final ExecutionInfo result = task.execute(checkLetterStatusJob(5));

            verify(statusService).markRequeued(notificationId);
            verify(statusService, never()).markFailed(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            final ArgumentCaptor<ExecutionInfo> resend = ArgumentCaptor.forClass(ExecutionInfo.class);
            verify(executionService).executeWith(resend.capture());
            assertThat(resend.getValue().getAssignedTaskName()).isEqualTo(SendLetterTask.TASK_NAME);
            assertThat(LetterJob.from(resend.getValue().getJobData()))
                    .isEqualTo(new LetterJob(notificationId, LETTER_URL, true, 4));
        }

        @Test
        void should_mark_failed_with_400_once_no_resends_are_left() {
            when(govNotifyClient.checkStatus(REFERENCE)).thenReturn(NotificationStatus.INVALID_REQUEST);

            final ExecutionInfo result = task.execute(checkLetterStatusJob(0));

            verify(statusService).markFailed(new NotificationFailure(notificationId, LETTER, 400,
                    "Validation failed for '" + notificationId + "' automatic resend attempt, remaining = 0",
                    DELIVERY_FAILED));
            verify(statusService, never()).markRequeued(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            verifyNoInteractions(executionService);
        }
    }

    @Nested
    class WhenTheLetterIsStillInProgress {

        @ParameterizedTest
        @EnumSource(value = NotificationStatus.class, names = {"CREATED", "SENDING", "PENDING_VIRUS_CHECK"})
        void should_poll_again(final NotificationStatus status) {
            when(govNotifyClient.checkStatus(REFERENCE)).thenReturn(status);

            final ExecutionInfo result = task.execute(checkLetterStatusJob(5));

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
            verifyNoInteractions(statusService, executionService);
        }

        @Test
        void should_mark_failed_once_its_retries_are_exhausted() {
            when(govNotifyClient.checkStatus(REFERENCE)).thenReturn(NotificationStatus.PENDING_VIRUS_CHECK);

            final ExecutionInfo result = task.execute(withRetriesRemaining(checkLetterStatusJob(5), 0));

            final ArgumentCaptor<NotificationFailure> notificationFailureCaptor =
                    ArgumentCaptor.forClass(NotificationFailure.class);
            verify(statusService).markFailed(notificationFailureCaptor.capture());
            final NotificationFailure notificationFailure = notificationFailureCaptor.getValue();
            assertThat(notificationFailure.notificationId()).isEqualTo(notificationId);
            assertThat(notificationFailure.channel()).isEqualTo(LETTER);
            assertThat(notificationFailure.statusCode()).isNull();
            assertThat(notificationFailure.errorMessage()).isEqualTo(
                    "Gov.Notify did not reach a terminal status within the retry window (last status 'pending-virus-check')");
            assertThat(notificationFailure.outcomeReason()).isEqualTo(STATUS_UNKNOWN);
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
    }

    @Nested
    class WhenGovNotifyReportsAnyOtherStatus {

        @ParameterizedTest
        @EnumSource(value = NotificationStatus.class, names = {
                "FAILED", "PERMANENT_FAILURE", "VIRUS_SCAN_FAILED", "TEMPORARY_FAILURE", "DELIVERED", "NOT_FOUND",
                "UNEXPECTED_FAILURE"})
        void should_mark_failed_at_once_with_the_status(final NotificationStatus status) {
            when(govNotifyClient.checkStatus(REFERENCE)).thenReturn(status);

            final ExecutionInfo result = task.execute(checkLetterStatusJob(5));

            final ArgumentCaptor<NotificationFailure> notificationFailureCaptor =
                    ArgumentCaptor.forClass(NotificationFailure.class);
            verify(statusService).markFailed(notificationFailureCaptor.capture());
            final NotificationFailure notificationFailure = notificationFailureCaptor.getValue();
            final String statusText = status.getStatus();
            assertThat(notificationFailure.notificationId()).isEqualTo(notificationId);
            assertThat(notificationFailure.channel()).isEqualTo(LETTER);
            assertThat(notificationFailure.statusCode()).isNull();
            assertThat(notificationFailure.errorMessage())
                    .isEqualTo("Gov.Notify responded with status '" + statusText + "'");
            assertThat(notificationFailure.outcomeReason()).isEqualTo(DELIVERY_FAILED);
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            verifyNoInteractions(executionService);
        }
    }

    @Nested
    class WhenThePollFails {

        @Test
        void should_poll_again_when_gov_notify_fails_transiently() {
            when(govNotifyClient.checkStatus(REFERENCE)).thenThrow(new GovNotifyException(500, "unavailable", null));

            final ExecutionInfo result = task.execute(checkLetterStatusJob(5));

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
            verifyNoInteractions(statusService);
        }

        @Test
        void should_mark_failed_when_gov_notify_keeps_failing_until_retries_are_exhausted() {
            when(govNotifyClient.checkStatus(REFERENCE)).thenThrow(new GovNotifyException(500, "unavailable", null));

            task.execute(withRetriesRemaining(checkLetterStatusJob(5), 0));

            verify(statusService).markFailed(new NotificationFailure(notificationId, LETTER, 500,
                    "Gov.Notify status polling did not recover within the retry window", PROVIDER_FAILED));
        }

        @Test
        void should_poll_again_when_the_tls_handshake_with_gov_notify_fails() {
            final SSLHandshakeException sslHandshakeException = new SSLHandshakeException("handshake failed");
            when(govNotifyClient.checkStatus(REFERENCE)).thenThrow(new GovNotifyException(
                    0, "javax.net.ssl.SSLHandshakeException: handshake failed", sslHandshakeException));

            final ExecutionInfo result = task.execute(checkLetterStatusJob(5));

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
            verifyNoInteractions(statusService);
        }

        @Test
        void should_mark_failed_saying_gov_notify_could_not_be_reached_when_retries_are_exhausted() {
            final SSLHandshakeException sslHandshakeException = new SSLHandshakeException("handshake failed");
            when(govNotifyClient.checkStatus(REFERENCE)).thenThrow(new GovNotifyException(
                    0, "javax.net.ssl.SSLHandshakeException: handshake failed", sslHandshakeException));

            task.execute(withRetriesRemaining(checkLetterStatusJob(5), 0));

            verify(statusService).markFailed(new NotificationFailure(notificationId, LETTER, null,
                    "Gov.Notify status polling did not recover within the retry window",
                    PROVIDER_UNREACHABLE));
        }

        @Test
        void should_mark_failed_at_once_when_gov_notify_fails_permanently() {
            when(govNotifyClient.checkStatus(REFERENCE)).thenThrow(new GovNotifyException(404, "not found", null));

            final ExecutionInfo result = task.execute(checkLetterStatusJob(5));

            verify(statusService).markFailed(
                    new NotificationFailure(notificationId, LETTER, 404, "not found", PROVIDER_REJECTED));
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
    }

    private ExecutionInfo checkLetterStatusJob(final int resendAttemptsRemaining) {
        return taskFactory.createCheckLetterStatusJob(
                new LetterJob(notificationId, LETTER_URL, true, resendAttemptsRemaining), REFERENCE);
    }

    private static ExecutionInfo withRetriesRemaining(final ExecutionInfo job, final int retriesRemaining) {
        return ExecutionInfo.executionInfo().from(job).withRetryAttemptsRemaining(retriesRemaining).build();
    }
}
