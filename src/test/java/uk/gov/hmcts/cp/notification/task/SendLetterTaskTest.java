package uk.gov.hmcts.cp.notification.task;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.json.JsonMapper;

import uk.gov.hmcts.cp.notification.logging.CapturedLogs;
import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;
import uk.gov.hmcts.cp.notification.logging.OutcomeReasonClassifier;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentDownloader;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.sender.Attachment;
import uk.gov.hmcts.cp.notification.sender.GovNotifyClient;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.GovNotifyFailureClassifier;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ch.qos.logback.classic.Level.INFO;
import static ch.qos.logback.classic.Level.WARN;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.TransientFailurePolicyFactory.aFailedNotificationRecorder;
import static uk.gov.hmcts.cp.notification.integration.testdata.TransientFailurePolicyFactory.aTransientFailurePolicy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.LETTER_URL;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_BAD_LINK;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_NOT_FOUND;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_UNAVAILABLE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_FAILED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;

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
    @Captor
    private ArgumentCaptor<NotificationFailure> notificationFailureCaptor;

    private final UUID notificationId = UUID.randomUUID();

    private SendLetterTask task;
    private CapturedLogs taskLogs;

    @BeforeEach
    void createTaskWithLegacyLetterRetryDurations() {
        final Clock clock = new Clock(java.time.Clock.fixed(NOW, ZoneOffset.UTC));
        final GovNotifyFailureClassifier govNotifyFailureClassifier = new GovNotifyFailureClassifier();
        final TaskRetrySchedule taskRetrySchedule = new TaskRetrySchedule(clock);
        task = new SendLetterTask(materialAttachmentDownloader, govNotifyClient,
                aTransientFailurePolicy(statusService, taskRetrySchedule),
                aFailedNotificationRecorder(statusService, taskRetrySchedule), taskRetrySchedule,
                executionService, new CpTaskFactory(JsonMapper.builder().build(), clock), govNotifyFailureClassifier,
                new FailureReasonClassifier(), new OutcomeReasonClassifier(), LEGACY_LETTER_RETRY_DURATIONS);
    }

    @BeforeEach
    void captureTheTaskLogs() {
        taskLogs = captureLogsOf(SendLetterTask.class);
    }

    @AfterEach
    void stopCapturingLogs() {
        taskLogs.close();
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

        @Test
        void should_log_a_successful_letter_send_to_gov_uk_notify() {
            aDownloadableLetter();
            when(govNotifyClient.sendPrecompiledLetter(any(), any(), anyBoolean())).thenReturn(REFERENCE);

            task.execute(sendLetterJob(false, 3));

            final List<ILoggingEvent> loggingEvents = taskLogs.events();
            assertThat(loggingEvents).hasSize(2);
            final ILoggingEvent sendingEvent = loggingEvents.getFirst();
            final String sendingMessage = sendingEvent.getFormattedMessage();
            assertThat(sendingMessage).isEqualTo(
                    "Sending letter for notification '" + notificationId + "' (first class: 'false')");
            assertThat(sendingEvent.getKeyValuePairs()).isNullOrEmpty();
            final ILoggingEvent sentEvent = loggingEvents.getLast();
            assertThat(sentEvent.getLevel()).isEqualTo(INFO);
            final String sentMessage = sentEvent.getFormattedMessage();
            assertThat(sentMessage).isEqualTo("Sent letter for notification '" + notificationId
                    + "' via Gov.Notify (reference 'notify-letter-ref')");
            final Map<String, Object> fields = fieldsOf(sentEvent);
            assertThat(fields).hasSize(4);
            assertThat(fields.get("dependency")).isEqualTo("gov-uk-notify");
            assertThat(fields.get("operation")).isEqualTo("send");
            assertThat(fields.get("channel")).isEqualTo("letter");
            assertThat(fields.get("outcome")).isEqualTo("succeeded");
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

            verify(statusService).markFailed(new NotificationFailure(notificationId, LETTER, 500,
                    "Gov.Notify letter send did not recover within the retry window", PROVIDER_FAILED));
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_fail_at_once_when_gov_uk_notify_did_not_respond_because_it_may_have_received_the_letter() {
            aDownloadableLetter();
            final SocketTimeoutException socketTimeoutException = new SocketTimeoutException("Read timed out");
            when(govNotifyClient.sendPrecompiledLetter(any(), any(), anyBoolean()))
                    .thenThrow(new GovNotifyException(
                            0, "java.net.SocketTimeoutException: Read timed out", socketTimeoutException));

            final ExecutionInfo result = task.execute(sendLetterJob(false, 5));

            verify(statusService).markFailed(new NotificationFailure(notificationId, LETTER, 0,
                    "java.net.SocketTimeoutException: Read timed out", PROVIDER_NO_RESPONSE));
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.isShouldRetry()).isFalse();
            verifyNoInteractions(executionService);
            final ILoggingEvent loggingEvent = lastLineIn(taskLogs);
            assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("Permanent letter send failure for notification '" + notificationId
                    + "' (http 'none') — marking FAILED");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("gov-uk-notify");
            assertThat(fields.get("operation")).isEqualTo("send");
            assertThat(fields.get("channel")).isEqualTo("letter");
            assertThat(fields.get("outcome")).isEqualTo("permanent-failure");
            assertThat(fields.get("reason")).isEqualTo("no-response");
        }

        @Test
        void should_mark_failed_without_retry_when_gov_notify_rejects_the_letter_as_a_bad_request() {
            aDownloadableLetter();
            when(govNotifyClient.sendPrecompiledLetter(any(), any(), anyBoolean()))
                    .thenThrow(new GovNotifyException(400, "precompiledPDF must be a valid PDF file", null));

            final ExecutionInfo result = task.execute(sendLetterJob(false, 5));

            verify(statusService).markFailed(new NotificationFailure(notificationId, LETTER, 400,
                    "precompiledPDF must be a valid PDF file", PROVIDER_REJECTED));
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            verifyNoInteractions(executionService);
        }

        @Test
        void should_log_a_permanent_gov_uk_notify_letter_send_failure() {
            aDownloadableLetter();
            when(govNotifyClient.sendPrecompiledLetter(any(), any(), anyBoolean()))
                    .thenThrow(new GovNotifyException(400, "precompiledPDF must be a valid PDF file", null));

            task.execute(sendLetterJob(false, 5));

            final ILoggingEvent loggingEvent = lastLineIn(taskLogs);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("Permanent letter send failure for notification '" + notificationId
                    + "' (http '400') — marking FAILED");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("gov-uk-notify");
            assertThat(fields.get("operation")).isEqualTo("send");
            assertThat(fields.get("channel")).isEqualTo("letter");
            assertThat(fields.get("outcome")).isEqualTo("permanent-failure");
            assertThat(fields.get("statusCode")).isEqualTo(400);
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

            verify(statusService).markFailed(new NotificationFailure(notificationId, LETTER, 500,
                    "Material letter download did not recover within the retry window", ATTACHMENT_UNAVAILABLE));
        }

        @Test
        void should_mark_failed_without_retry_when_the_letter_link_is_refused() {
            when(materialAttachmentDownloader.download(LETTER_URL, notificationId))
                    .thenThrow(MaterialAttachmentException.permanent("Material link is not on the Material service", null));

            final ExecutionInfo result = task.execute(sendLetterJob(false, 5));

            verify(statusService).markFailed(notificationFailureCaptor.capture());
            final NotificationFailure notificationFailure = notificationFailureCaptor.getValue();
            assertThat(notificationFailure.notificationId()).isEqualTo(notificationId);
            assertThat(notificationFailure.channel()).isEqualTo(LETTER);
            assertThat(notificationFailure.statusCode()).isNull();
            assertThat(notificationFailure.errorMessage()).isEqualTo("Material link is not on the Material service");
            assertThat(notificationFailure.outcomeReason()).isEqualTo(ATTACHMENT_BAD_LINK);
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            verify(govNotifyClient, never()).sendPrecompiledLetter(any(), any(), anyBoolean());
        }

        @Test
        void should_mark_failed_without_retry_as_attachment_not_found_when_material_has_no_such_letter() {
            when(materialAttachmentDownloader.download(LETTER_URL, notificationId))
                    .thenThrow(MaterialAttachmentException.permanent("Material has no such document (HTTP 404)", 404));

            final ExecutionInfo result = task.execute(sendLetterJob(false, 5));

            verify(statusService).markFailed(new NotificationFailure(notificationId, LETTER, 404,
                    "Material has no such document (HTTP 404)", ATTACHMENT_NOT_FOUND));
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.isShouldRetry()).isFalse();
            verify(govNotifyClient, never()).sendPrecompiledLetter(any(), any(), anyBoolean());
        }

        @Test
        void should_log_a_permanent_material_letter_download_failure_with_no_status_code() {
            when(materialAttachmentDownloader.download(LETTER_URL, notificationId))
                    .thenThrow(MaterialAttachmentException.permanent("Material link is not on the Material service", null));

            task.execute(sendLetterJob(false, 5));

            final List<ILoggingEvent> loggingEvents = taskLogs.events();
            assertThat(loggingEvents).hasSize(1);
            final ILoggingEvent loggingEvent = loggingEvents.getFirst();
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("Permanent Material letter download failure for notification '"
                    + notificationId + "' ('Material link is not on the Material service') — marking FAILED, no retry");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(4);
            assertThat(fields.get("dependency")).isEqualTo("material");
            assertThat(fields.get("operation")).isEqualTo("download");
            assertThat(fields.get("channel")).isEqualTo("letter");
            assertThat(fields.get("outcome")).isEqualTo("permanent-failure");
            assertThat(fields.containsKey("statusCode")).isFalse();
        }
    }

    private static ILoggingEvent lastLineIn(final CapturedLogs capturedLogs) {
        final List<ILoggingEvent> loggingEvents = capturedLogs.events();
        assertThat(loggingEvents).isNotEmpty();
        return loggingEvents.getLast();
    }

    private static Map<String, Object> fieldsOf(final ILoggingEvent loggingEvent) {
        final Map<String, Object> fields = new HashMap<>();
        final List<KeyValuePair> keyValuePairs = loggingEvent.getKeyValuePairs();
        for (final KeyValuePair keyValuePair : keyValuePairs) {
            fields.put(keyValuePair.key, keyValuePair.value);
        }
        return fields;
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
