package uk.gov.hmcts.cp.notification.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import uk.gov.hmcts.cp.notification.material.MaterialAttachmentDownloader;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.sender.Attachment;
import uk.gov.hmcts.cp.notification.sender.GovNotifyClient;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.LETTER_URL;

@ExtendWith(MockitoExtension.class)
class SendLetterTaskTest {
    private static final List<Long> LEGACY_LETTER_RETRY_DURATIONS =
            List.of(60L, 300L, 1800L, 3600L, 7200L, 14400L, 14400L);
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final byte[] PDF = "%PDF-1.4 letter".getBytes(StandardCharsets.UTF_8);
    private static final String REFERENCE = "notify-letter-ref";

    @Mock
    private MaterialAttachmentDownloader materialAttachmentDownloader;
    @Mock
    private GovNotifyClient govNotifyClient;
    @Mock
    private NotificationStatusService statusService;
    @Mock
    private ExecutionService executionService;

    private final UUID notificationId = UUID.randomUUID();

    private SendLetterTask task;

    @BeforeEach
    void createTaskWithLegacyLetterRetryDurations() {
        final Clock clock = new Clock(java.time.Clock.fixed(NOW, ZoneOffset.UTC));
        task = new SendLetterTask(materialAttachmentDownloader, govNotifyClient,
                new TransientFailurePolicy(statusService, clock, List.of(1L)),
                executionService, new CpTaskFactory(JsonMapper.builder().build(), clock), LEGACY_LETTER_RETRY_DURATIONS);
    }

    @Test
    void should_preserve_the_legacy_letter_retry_durations() {
        assertThat(task.getRetryDurationsInSecs()).hasValue(LEGACY_LETTER_RETRY_DURATIONS);
    }

    @Nested
    class WhenSendingSucceeds {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void should_send_the_material_document_with_its_postage_and_enqueue_a_status_check(final boolean firstClass) {
            when(materialAttachmentDownloader.download(LETTER_URL, notificationId)).thenReturn(new Attachment(PDF, "letter.pdf"));
            when(govNotifyClient.sendPrecompiledLetter(notificationId, PDF, firstClass)).thenReturn(REFERENCE);

            final ExecutionInfo result = task.execute(sendLetterJob(firstClass, 3));

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            final ArgumentCaptor<ExecutionInfo> next = ArgumentCaptor.forClass(ExecutionInfo.class);
            verify(executionService).executeWith(next.capture());
            assertThat(next.getValue().getAssignedTaskName()).isEqualTo(CheckLetterStatusTask.TASK_NAME);
            assertThat(next.getValue().getJobData().getString(CheckLetterStatusTask.KEY_REFERENCE)).isEqualTo(REFERENCE);
            assertThat(LetterJob.from(next.getValue().getJobData()))
                    .isEqualTo(new LetterJob(notificationId, LETTER_URL, firstClass, 3));
            verifyNoInteractions(statusService);
        }
    }

    @Nested
    class WhenGovNotifyRejectsTheLetter {

        @Test
        void should_retry_when_gov_notify_fails_transiently() {
            aDownloadableLetter();
            when(govNotifyClient.sendPrecompiledLetter(any(), any(), anyBoolean()))
                    .thenThrow(new GovNotifyException(500, "Status code: 500 Internal Server Error", null));

            final ExecutionInfo result = task.execute(sendLetterJob(false, 5));

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
            verifyNoInteractions(statusService, executionService);
        }

        @Test
        void should_schedule_the_final_attempt_after_the_last_letter_retry_duration() {
            aDownloadableLetter();
            when(govNotifyClient.sendPrecompiledLetter(any(), any(), anyBoolean()))
                    .thenThrow(new GovNotifyException(503, "Status code: 503", null));

            final ExecutionInfo result = task.execute(sendLetterJobWithRetriesRemaining(1));

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isFalse();
            assertThat(result.getAssignedTaskStartTime())
                    .isEqualTo(ZonedDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(14400));
        }

        @Test
        void should_mark_failed_when_gov_notify_keeps_failing_until_retries_are_exhausted() {
            aDownloadableLetter();
            when(govNotifyClient.sendPrecompiledLetter(any(), any(), anyBoolean()))
                    .thenThrow(new GovNotifyException(500, "Status code: 500 Internal Server Error", null));

            final ExecutionInfo result = task.execute(sendLetterJobWithRetriesRemaining(0));

            verify(statusService).markFailed(notificationId, 500,
                    "Gov.Notify letter send did not recover within the retry window");
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_mark_failed_without_retry_when_gov_notify_rejects_the_letter_as_a_bad_request() {
            aDownloadableLetter();
            when(govNotifyClient.sendPrecompiledLetter(any(), any(), anyBoolean()))
                    .thenThrow(new GovNotifyException(400, "precompiledPDF must be a valid PDF file", null));

            final ExecutionInfo result = task.execute(sendLetterJob(false, 5));

            verify(statusService).markFailed(notificationId, 400, "precompiledPDF must be a valid PDF file");
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            verifyNoInteractions(executionService);
        }
    }

    @Nested
    class WhenTheLetterCannotBeDownloaded {

        @Test
        void should_retry_without_sending_when_material_is_briefly_unavailable() {
            when(materialAttachmentDownloader.download(LETTER_URL, notificationId))
                    .thenThrow(MaterialAttachmentException.retryable("Material did not return the document (HTTP 500)", 500));

            final ExecutionInfo result = task.execute(sendLetterJob(false, 5));

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
            verify(govNotifyClient, never()).sendPrecompiledLetter(any(), any(), anyBoolean());
            verifyNoInteractions(statusService);
        }

        @Test
        void should_mark_failed_with_the_last_status_when_material_stays_unavailable_until_retries_are_exhausted() {
            when(materialAttachmentDownloader.download(LETTER_URL, notificationId))
                    .thenThrow(MaterialAttachmentException.retryable("Material did not return the document (HTTP 500)", 500));

            task.execute(sendLetterJobWithRetriesRemaining(0));

            verify(statusService).markFailed(notificationId, 500,
                    "Material letter download did not recover within the retry window");
        }

        @Test
        void should_mark_failed_without_retry_when_the_letter_link_is_refused() {
            when(materialAttachmentDownloader.download(LETTER_URL, notificationId))
                    .thenThrow(MaterialAttachmentException.permanent("Material link is not on the Material service", null));

            final ExecutionInfo result = task.execute(sendLetterJob(false, 5));

            verify(statusService).markFailed(eq(notificationId), isNull(),
                    eq("Material link is not on the Material service"));
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            verify(govNotifyClient, never()).sendPrecompiledLetter(any(), any(), anyBoolean());
        }
    }

    private void aDownloadableLetter() {
        when(materialAttachmentDownloader.download(LETTER_URL, notificationId)).thenReturn(new Attachment(PDF, "letter.pdf"));
    }

    private ExecutionInfo sendLetterJob(final boolean firstClass, final int resendAttemptsRemaining) {
        return new CpTaskFactory(JsonMapper.builder().build(), new Clock())
                .createSendLetterJob(new LetterJob(notificationId, LETTER_URL, firstClass, resendAttemptsRemaining));
    }

    private ExecutionInfo sendLetterJobWithRetriesRemaining(final int retriesRemaining) {
        return ExecutionInfo.executionInfo()
                .from(sendLetterJob(false, 5))
                .withRetryAttemptsRemaining(retriesRemaining)
                .build();
    }
}
