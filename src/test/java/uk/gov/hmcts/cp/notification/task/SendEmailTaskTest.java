package uk.gov.hmcts.cp.notification.task;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.models.BlobStorageException;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import uk.gov.hmcts.cp.notification.blob.AttachmentDownloader;
import uk.gov.hmcts.cp.notification.logging.CapturedLogs;
import uk.gov.hmcts.cp.notification.logging.FailureReasonClassifier;
import uk.gov.hmcts.cp.notification.logging.OutcomeReason;
import uk.gov.hmcts.cp.notification.logging.OutcomeReasonClassifier;
import uk.gov.hmcts.cp.notification.blob.PermanentBlobException;
import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentDownloader;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.sender.Attachment;
import uk.gov.hmcts.cp.notification.sender.EmailSender;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.GovNotifyFailureClassifier;
import uk.gov.hmcts.cp.notification.sender.Office365Exception;
import uk.gov.hmcts.cp.notification.service.NotificationFailure;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import javax.net.ssl.SSLHandshakeException;

import static ch.qos.logback.classic.Level.WARN;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.TransientFailurePolicyFactory.aFailedNotificationRecorder;
import static uk.gov.hmcts.cp.notification.integration.testdata.TransientFailurePolicyFactory.aTransientFailurePolicy;
import static org.mockito.ArgumentMatchers.any;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_ACCESS_REFUSED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_BAD_LINK;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_NOT_FOUND;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_TOO_LARGE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_UNAVAILABLE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_FAILED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_UNREACHABLE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.integration.testdata.AttachmentFactory.aMaterialDocumentAttachment;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.MATERIAL_URL;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendResultFactory.aSendResult;

@ExtendWith(MockitoExtension.class)
class SendEmailTaskTest {
    private static final List<Long> LEGACY_EMAIL_RETRY_DURATIONS =
            List.of(60L, 300L, 1800L, 3600L, 7200L, 14400L);

    @Mock
    private AttachmentDownloader attachmentDownloader;
    @Mock
    private MaterialAttachmentDownloader materialAttachmentDownloader;
    @Mock
    private EmailSender emailSender;
    @Mock
    private NotificationStatusService statusService;
    @Mock
    private ExecutionService executionService;
    @Mock
    private Clock clock;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private SendEmailTask task;
    private CapturedLogs taskLogs;
    private CapturedLogs policyLogs;

    @Captor
    private ArgumentCaptor<Attachment> attachmentCaptor;
    @Captor
    private ArgumentCaptor<ExecutionInfo> executionInfoCaptor;
    @Captor
    private ArgumentCaptor<NotificationFailure> notificationFailureCaptor;

    @BeforeEach
    void createTaskWithLegacyEmailRetryDurations() {
        final GovNotifyFailureClassifier govNotifyFailureClassifier = new GovNotifyFailureClassifier();
        final TaskRetrySchedule taskRetrySchedule = new TaskRetrySchedule(new Clock());
        task = new SendEmailTask(attachmentDownloader, materialAttachmentDownloader, emailSender,
                aTransientFailurePolicy(statusService, taskRetrySchedule),
                aFailedNotificationRecorder(statusService, taskRetrySchedule), taskRetrySchedule,
                executionService, objectMapper, new CpTaskFactory(objectMapper, clock), govNotifyFailureClassifier,
                new FailureReasonClassifier(), new OutcomeReasonClassifier(), LEGACY_EMAIL_RETRY_DURATIONS);
    }

    @BeforeEach
    void captureTheTaskAndRetryPolicyLogs() {
        taskLogs = captureLogsOf(SendEmailTask.class);
        policyLogs = captureLogsOf(TransientFailurePolicy.class);
    }

    @AfterEach
    void stopCapturingLogs() {
        taskLogs.close();
        policyLogs.close();
    }

    @Test
    void should_preserve_the_legacy_email_retry_durations() {
        assertThat(task.getRetryDurationsInSecs()).hasValue(LEGACY_EMAIL_RETRY_DURATIONS);
    }

    @Nested
    class WhenSendingSucceeds {

        @Test
        void should_download_attachment_and_pass_it_to_the_email_sender_when_file_uri_present() {
            final UUID id = UUID.randomUUID();
            final String fileUri = "https://sa.blob.core.windows.net/mi-reportdata/report.csv";
            final byte[] bytes = "report".getBytes(StandardCharsets.UTF_8);
            when(attachmentDownloader.download(fileUri)).thenReturn(bytes);
            when(emailSender.sendEmail(any(SendEmailCommand.class), any())).thenReturn(aSendResult().build());

            task.execute(sendEmailJob(id, fileUri));

            verify(attachmentDownloader).download(fileUri);
            verify(emailSender).sendEmail(any(SendEmailCommand.class), attachmentCaptor.capture());
            assertThat(attachmentCaptor.getValue().content()).isEqualTo(bytes);
            assertThat(attachmentCaptor.getValue().filename()).isNull();
            verifyNoInteractions(materialAttachmentDownloader);
        }

        @Test
        void should_download_the_material_document_and_pass_it_with_its_filename_when_material_url_present() {
            final UUID id = UUID.randomUUID();
            final Attachment document = aMaterialDocumentAttachment();
            when(materialAttachmentDownloader.download(MATERIAL_URL, id)).thenReturn(document);
            when(emailSender.sendEmail(any(SendEmailCommand.class), any())).thenReturn(aSendResult().build());

            task.execute(sendEmailJob(id, "", MATERIAL_URL));

            verify(emailSender).sendEmail(any(SendEmailCommand.class), attachmentCaptor.capture());
            assertThat(attachmentCaptor.getValue()).isEqualTo(document);
            verify(attachmentDownloader, never()).download(any());
        }

        @Test
        void should_attach_only_the_material_document_when_both_a_material_url_and_a_file_uri_are_present() {
            final UUID id = UUID.randomUUID();
            final Attachment document = aMaterialDocumentAttachment();
            when(materialAttachmentDownloader.download(MATERIAL_URL, id)).thenReturn(document);
            when(emailSender.sendEmail(any(SendEmailCommand.class), any())).thenReturn(aSendResult().build());

            task.execute(sendEmailJob(id, "https://sa.blob.core.windows.net/mi-reportdata/report.csv", MATERIAL_URL));

            verify(attachmentDownloader, never()).download(any());
            verify(emailSender).sendEmail(any(SendEmailCommand.class), attachmentCaptor.capture());
            assertThat(attachmentCaptor.getValue()).isEqualTo(document);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void should_attach_the_file_uri_blob_when_the_material_url_is_blank(final String blankMaterialUrl) {
            final UUID id = UUID.randomUUID();
            final String fileUri = "https://sa.blob.core.windows.net/mi-reportdata/report.csv";
            final byte[] bytes = "report".getBytes(StandardCharsets.UTF_8);
            when(attachmentDownloader.download(fileUri)).thenReturn(bytes);
            when(emailSender.sendEmail(any(SendEmailCommand.class), any())).thenReturn(aSendResult().build());

            task.execute(sendEmailJob(id, fileUri, blankMaterialUrl));

            verifyNoInteractions(materialAttachmentDownloader);
            verify(emailSender).sendEmail(any(SendEmailCommand.class), attachmentCaptor.capture());
            assertThat(attachmentCaptor.getValue().content()).isEqualTo(bytes);
        }

        @Test
        void should_send_without_attachment_when_file_uri_is_blank() {
            final UUID id = UUID.randomUUID();
            when(emailSender.sendEmail(any(SendEmailCommand.class), any())).thenReturn(aSendResult().build());

            task.execute(sendEmailJob(id, ""));

            verify(attachmentDownloader, never()).download(any());
            verify(emailSender).sendEmail(any(SendEmailCommand.class), attachmentCaptor.capture());
            assertThat(attachmentCaptor.getValue()).isNull();
        }

        @Test
        void should_schedule_check_email_status_carrying_the_reference_in_job_data_and_not_on_the_row() {
            final UUID id = UUID.randomUUID();
            final String fileUri = "https://sa.blob.core.windows.net/mi-reportdata/report.csv";
            final String externalReference = "1490dab7-2b48-4a9a-9f8a-2f0d0e2e6b11";
            when(attachmentDownloader.download(fileUri)).thenReturn("report".getBytes(StandardCharsets.UTF_8));
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenReturn(aSendResult().reference(externalReference).build());

            final ExecutionInfo result = task.execute(sendEmailJob(id, fileUri));

            verify(executionService).executeWith(executionInfoCaptor.capture());
            final ExecutionInfo scheduled = executionInfoCaptor.getValue();
            assertThat(scheduled.getAssignedTaskName()).isEqualTo(CheckEmailStatusTask.TASK_NAME);
            assertThat(scheduled.getJobData().toString()).contains(externalReference).contains(id.toString());

            verifyNoInteractions(statusService);
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_schedule_record_sent_with_the_email_details_instead_of_a_status_check_when_office365_has_sent_the_email() {
            final UUID id = UUID.randomUUID();
            when(emailSender.sendEmail(any(SendEmailCommand.class), any())).thenReturn(aSendResult()
                    .reference(id.toString())
                    .emailSubject("Your document is ready")
                    .emailBody("Please find the document attached.")
                    .replyToAddress("NOREPLY@noreply.com")
                    .sentOnSubmit(true)
                    .build());

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            verify(executionService).executeWith(executionInfoCaptor.capture());
            final ExecutionInfo scheduled = executionInfoCaptor.getValue();
            assertThat(scheduled.getAssignedTaskName()).isEqualTo(RecordSentTask.TASK_NAME);
            assertThat(scheduled.getJobData().getString(RecordSentTask.KEY_NOTIFICATION_ID)).isEqualTo(id.toString());
            assertThat(scheduled.getJobData().getString(RecordSentTask.KEY_EMAIL_SUBJECT)).isEqualTo("Your document is ready");
            verifyNoInteractions(statusService);
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
    }

    @Nested
    class WhenAFailureIsPermanent {
        private final UUID notificationId = UUID.fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80");

        @Test
        void should_log_a_permanent_blob_storage_download_failure() {
            final String fileUri = "https://sa.blob.core.windows.net/mi-reportdata/missing.csv";
            when(attachmentDownloader.download(fileUri)).thenThrow(new PermanentBlobException("blob 404", 404, null));

            task.execute(sendEmailJob(notificationId, fileUri));

            final ILoggingEvent loggingEvent = onlyLineIn(taskLogs);
            assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("Permanent attachment failure for notification"
                    + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' — marking FAILED, no retry");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("blob-storage");
            assertThat(fields.get("operation")).isEqualTo("download");
            assertThat(fields.get("channel")).isEqualTo("email");
            assertThat(fields.get("outcome")).isEqualTo("permanent-failure");
            assertThat(fields.get("statusCode")).isEqualTo(404);
        }

        @Test
        void should_log_a_permanent_material_download_failure() {
            when(materialAttachmentDownloader.download(MATERIAL_URL, notificationId)).thenThrow(
                    MaterialAttachmentException.permanent("Material rejected the document request (HTTP 400)", 400));

            task.execute(sendEmailJob(notificationId, "", MATERIAL_URL));

            final ILoggingEvent loggingEvent = onlyLineIn(taskLogs);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("Permanent Material attachment failure for notification"
                    + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' ('Material rejected the document request (HTTP 400)')"
                    + " — marking FAILED, no retry");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("material");
            assertThat(fields.get("operation")).isEqualTo("download");
            assertThat(fields.get("channel")).isEqualTo("email");
            assertThat(fields.get("outcome")).isEqualTo("permanent-failure");
            assertThat(fields.get("statusCode")).isEqualTo(400);
        }

        @Test
        void should_log_a_permanent_gov_uk_notify_send_failure() {
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new GovNotifyException(400, "bad request", null));

            task.execute(sendEmailJob(notificationId, ""));

            final ILoggingEvent loggingEvent = onlyLineIn(taskLogs);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("Permanent send failure for notification"
                    + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (http '400') — marking FAILED");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("gov-uk-notify");
            assertThat(fields.get("operation")).isEqualTo("send");
            assertThat(fields.get("channel")).isEqualTo("email");
            assertThat(fields.get("outcome")).isEqualTo("permanent-failure");
            assertThat(fields.get("statusCode")).isEqualTo(400);
        }

        @Test
        void should_log_a_permanent_microsoft_365_send_failure() {
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new Office365Exception(413, "Office 365 responded with 'Request too large'", null));

            task.execute(sendEmailJob(notificationId, ""));

            final ILoggingEvent loggingEvent = onlyLineIn(taskLogs);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("Permanent Office 365 send failure for notification"
                    + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (http '413') — marking FAILED");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("microsoft-365");
            assertThat(fields.get("operation")).isEqualTo("send");
            assertThat(fields.get("channel")).isEqualTo("email");
            assertThat(fields.get("outcome")).isEqualTo("permanent-failure");
            assertThat(fields.get("statusCode")).isEqualTo(413);
        }
    }

    @Nested
    class WhenSendingFails {

        @ParameterizedTest
        @MethodSource("uk.gov.hmcts.cp.notification.task.SendEmailTaskTest#permanentMaterialFailures")
        void should_mark_failed_with_the_failure_status_and_complete_without_retry_when_the_material_download_fails_permanently(
                final MaterialAttachmentException failure, final OutcomeReason outcomeReason) {
            final UUID id = UUID.randomUUID();
            when(materialAttachmentDownloader.download(MATERIAL_URL, id)).thenThrow(failure);

            final ExecutionInfo result = task.execute(sendEmailJob(id, "", MATERIAL_URL));

            final Integer statusCode = failure.getStatusCode();
            final String errorMessage = failure.getMessage();
            verify(statusService).markFailed(new NotificationFailure(id, EMAIL, statusCode, errorMessage, outcomeReason));
            verify(emailSender, never()).sendEmail(any(), any());
            verify(executionService, never()).executeWith(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.isShouldRetry()).isFalse();
        }

        @Test
        void should_retry_when_the_material_download_fails_retryably() {
            final UUID id = UUID.randomUUID();
            when(materialAttachmentDownloader.download(MATERIAL_URL, id)).thenThrow(
                    MaterialAttachmentException.retryable("Material did not return the document (HTTP 503)", 503));

            final ExecutionInfo result = task.execute(sendEmailJob(id, "", MATERIAL_URL));

            verify(statusService, never()).markFailed(any());
            verify(emailSender, never()).sendEmail(any(), any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
        }

        @Test
        void should_mark_failed_with_the_last_status_when_the_material_download_keeps_failing_until_retries_are_exhausted() {
            final UUID id = UUID.randomUUID();
            when(materialAttachmentDownloader.download(MATERIAL_URL, id)).thenThrow(
                    MaterialAttachmentException.retryable("Material did not return the document (HTTP 502)", 502));

            final ExecutionInfo result = task.execute(sendEmailJobWithRetriesRemaining(id, "", MATERIAL_URL, 0));

            verify(statusService).markFailed(new NotificationFailure(id, EMAIL, 502,
                    "Material attachment download did not recover within the retry window", ATTACHMENT_UNAVAILABLE));
            verify(emailSender, never()).sendEmail(any(), any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_mark_failed_with_no_status_when_a_timeout_keeps_the_material_download_failing_until_retries_are_exhausted() {
            final UUID id = UUID.randomUUID();
            when(materialAttachmentDownloader.download(MATERIAL_URL, id)).thenThrow(
                    MaterialAttachmentException.retryable("Material did not answer in time", null));

            task.execute(sendEmailJobWithRetriesRemaining(id, "", MATERIAL_URL, 0));

            verify(statusService).markFailed(new NotificationFailure(id, EMAIL, null,
                    "Material attachment download did not recover within the retry window", ATTACHMENT_UNAVAILABLE));
        }

        @Test
        void should_mark_failed_and_complete_without_retry_when_blob_download_is_permanently_failed() {
            final UUID id = UUID.randomUUID();
            final String fileUri = "https://sa.blob.core.windows.net/mi-reportdata/missing.csv";
            when(attachmentDownloader.download(fileUri))
                    .thenThrow(new PermanentBlobException("blob 404", 404, null));

            final ExecutionInfo result = task.execute(sendEmailJob(id, fileUri));

            verify(statusService).markFailed(new NotificationFailure(id, EMAIL, 404, "blob 404", ATTACHMENT_NOT_FOUND));
            verify(emailSender, never()).sendEmail(any(), any());
            verify(executionService, never()).executeWith(any());

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_retry_when_blob_download_fails_transiently() {
            final UUID id = UUID.randomUUID();
            final String fileUri = "https://sa.blob.core.windows.net/mi-reportdata/report.csv";
            final BlobStorageException transientError = blobStorageException(500);
            when(attachmentDownloader.download(fileUri)).thenThrow(transientError);

            final ExecutionInfo result = task.execute(sendEmailJob(id, fileUri));

            verify(statusService, never()).markFailed(any());
            verify(emailSender, never()).sendEmail(any(), any());
            verify(executionService, never()).executeWith(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
        }

        @Test
        void should_mark_failed_and_complete_when_blob_download_keeps_failing_transiently_until_retries_are_exhausted() {
            final UUID id = UUID.randomUUID();
            final String fileUri = "https://sa.blob.core.windows.net/mi-reportdata/report.csv";
            final BlobStorageException transientError = blobStorageException(503);
            when(attachmentDownloader.download(fileUri)).thenThrow(transientError);

            final ExecutionInfo result = task.execute(sendEmailJobWithRetriesRemaining(id, fileUri, 0));

            verify(statusService).markFailed(notificationFailureCaptor.capture());
            final NotificationFailure notificationFailure = notificationFailureCaptor.getValue();
            assertThat(notificationFailure.notificationId()).isEqualTo(id);
            assertThat(notificationFailure.channel()).isEqualTo(EMAIL);
            assertThat(notificationFailure.statusCode()).isEqualTo(503);
            assertThat(notificationFailure.errorMessage())
                    .isEqualTo("Attachment download did not recover within the retry window");
            assertThat(notificationFailure.outcomeReason()).isEqualTo(ATTACHMENT_UNAVAILABLE);
            verify(emailSender, never()).sendEmail(any(), any());
            verify(executionService, never()).executeWith(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_mark_failed_and_complete_without_retry_when_send_fails_permanently() {
            final UUID id = UUID.randomUUID();
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new GovNotifyException(400, "bad request", null));

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            verify(statusService).markFailed(notificationFailureCaptor.capture());
            final NotificationFailure notificationFailure = notificationFailureCaptor.getValue();
            assertThat(notificationFailure.notificationId()).isEqualTo(id);
            assertThat(notificationFailure.channel()).isEqualTo(EMAIL);
            assertThat(notificationFailure.statusCode()).isEqualTo(400);
            assertThat(notificationFailure.errorMessage()).isEqualTo("bad request");
            assertThat(notificationFailure.outcomeReason()).isEqualTo(PROVIDER_REJECTED);
            verify(executionService, never()).executeWith(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @ParameterizedTest
        @ValueSource(ints = {400, 413})
        void should_mark_failed_and_complete_without_retry_when_office365_rejects_the_email(final int status) {
            final UUID id = UUID.randomUUID();
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new Office365Exception(status, "Office 365 responded with 'Invalid request payload'", null));

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            verify(statusService).markFailed(new NotificationFailure(id, EMAIL, status,
                    "Office 365 responded with 'Invalid request payload'", PROVIDER_REJECTED));
            verify(executionService, never()).executeWith(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.isShouldRetry()).isFalse();
        }

        @Test
        void should_mark_failed_without_a_status_and_without_retry_when_the_office365_request_cannot_be_built() {
            final UUID id = UUID.randomUUID();
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(Office365Exception.unsendable("Office 365 request could not be built", null));

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            verify(statusService).markFailed(new NotificationFailure(id, EMAIL, null,
                    "Office 365 request could not be built", PROVIDER_REJECTED));
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.isShouldRetry()).isFalse();
        }

        @Test
        void should_retry_when_office365_fails_retryably() {
            final UUID id = UUID.randomUUID();
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new Office365Exception(null, "Office 365 could not be reached", null));

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            verify(statusService, never()).markFailed(any());
            verify(statusService, never()).markSent(any(), any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
        }

        @Test
        void should_mark_failed_with_the_last_status_when_office365_keeps_failing_until_retries_are_exhausted() {
            final UUID id = UUID.randomUUID();
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new Office365Exception(503, "Office 365 responded with 'unavailable'", null));

            final ExecutionInfo result = task.execute(sendEmailJobWithRetriesRemaining(id, "", 0));

            verify(statusService).markFailed(new NotificationFailure(id, EMAIL, 503,
                    "Office 365 send did not recover within the retry window", PROVIDER_FAILED));
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_retry_when_send_fails_transiently() {
            final UUID id = UUID.randomUUID();
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new GovNotifyException(500, "Gov.Notify unavailable", null));

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            verify(statusService, never()).markFailed(any());
            verify(executionService, never()).executeWith(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
        }

        @Test
        void should_mark_failed_and_complete_when_send_keeps_failing_transiently_until_retries_are_exhausted() {
            final UUID id = UUID.randomUUID();
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new GovNotifyException(500, "Gov.Notify unavailable", null));

            final ExecutionInfo result = task.execute(sendEmailJobWithRetriesRemaining(id, "", 0));

            verify(statusService).markFailed(notificationFailureCaptor.capture());
            final NotificationFailure notificationFailure = notificationFailureCaptor.getValue();
            assertThat(notificationFailure.notificationId()).isEqualTo(id);
            assertThat(notificationFailure.channel()).isEqualTo(EMAIL);
            assertThat(notificationFailure.statusCode()).isEqualTo(500);
            assertThat(notificationFailure.errorMessage())
                    .isEqualTo("Gov.Notify send did not recover within the retry window");
            assertThat(notificationFailure.outcomeReason()).isEqualTo(PROVIDER_FAILED);
            verify(executionService, never()).executeWith(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_retry_and_log_that_gov_uk_notify_could_not_be_reached_when_the_tls_handshake_fails() {
            final UUID id = UUID.fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80");
            final SSLHandshakeException sslHandshakeException = new SSLHandshakeException("handshake failed");
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new GovNotifyException(
                            0, "javax.net.ssl.SSLHandshakeException: handshake failed", sslHandshakeException));

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            verify(statusService, never()).markFailed(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
            final ILoggingEvent loggingEvent = onlyLineIn(policyLogs);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("Transient failure 'invoking the Gov.Notify send API' for notificationId"
                    + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (the service could not be reached) — will retry");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("gov-uk-notify");
            assertThat(fields.get("operation")).isEqualTo("send");
            assertThat(fields.get("channel")).isEqualTo("email");
            assertThat(fields.get("outcome")).isEqualTo("transient-failure");
            assertThat(fields.get("reason")).isEqualTo("unreachable");
        }

        @Test
        void should_mark_failed_as_provider_unreachable_when_retries_are_exhausted() {
            final UUID id = UUID.randomUUID();
            final SSLHandshakeException sslHandshakeException = new SSLHandshakeException("handshake failed");
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new GovNotifyException(
                            0, "javax.net.ssl.SSLHandshakeException: handshake failed", sslHandshakeException));

            final ExecutionInfo result = task.execute(sendEmailJobWithRetriesRemaining(id, "", 0));

            verify(statusService).markFailed(new NotificationFailure(id, EMAIL, null,
                    "Gov.Notify send did not recover within the retry window", PROVIDER_UNREACHABLE));
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_fail_at_once_when_the_connection_to_gov_uk_notify_is_refused_because_it_may_have_sent() {
            final UUID id = UUID.randomUUID();
            final ConnectException connectException = new ConnectException("Connection refused");
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new GovNotifyException(0, "java.net.ConnectException: Connection refused", connectException));

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            verify(statusService).markFailed(new NotificationFailure(id, EMAIL, 0,
                    "java.net.ConnectException: Connection refused", PROVIDER_UNREACHABLE));
            verify(executionService, never()).executeWith(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.isShouldRetry()).isFalse();
        }

        @Test
        void should_log_that_gov_uk_notify_could_not_be_reached_when_the_connection_is_refused_so_the_outage_alert_counts_it() {
            final UUID id = UUID.randomUUID();
            final ConnectException connectException = new ConnectException("Connection refused");
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new GovNotifyException(0, "java.net.ConnectException: Connection refused", connectException));

            task.execute(sendEmailJob(id, ""));

            final ILoggingEvent loggingEvent = onlyLineIn(taskLogs);
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("gov-uk-notify");
            assertThat(fields.get("operation")).isEqualTo("send");
            assertThat(fields.get("outcome")).isEqualTo("permanent-failure");
            assertThat(fields.get("reason")).isEqualTo("unreachable");
            assertThat(fields.containsKey("statusCode")).isFalse();
        }

        @Test
        void should_mark_failed_at_once_when_gov_uk_notify_gives_no_http_response_without_an_input_output_failure() {
            final UUID id = UUID.randomUUID();
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new GovNotifyException(0, "reference cannot be null or empty", null));

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            verify(statusService).markFailed(new NotificationFailure(id, EMAIL, 0,
                    "reference cannot be null or empty", PROVIDER_REJECTED));
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_fail_at_once_when_gov_uk_notify_did_not_respond_because_it_may_have_sent() {
            final UUID id = UUID.fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80");
            final HttpTimeoutException httpTimeoutException = new HttpTimeoutException("request timed out");
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new GovNotifyException(0, "java.net.http.HttpTimeoutException: request timed out",
                            httpTimeoutException));

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            verify(statusService).markFailed(new NotificationFailure(id, EMAIL, 0,
                    "java.net.http.HttpTimeoutException: request timed out", PROVIDER_NO_RESPONSE));
            verify(executionService, never()).executeWith(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.isShouldRetry()).isFalse();
            final ILoggingEvent loggingEvent = onlyLineIn(taskLogs);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("Permanent send failure for notification"
                    + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (http 'none') — marking FAILED");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("gov-uk-notify");
            assertThat(fields.get("operation")).isEqualTo("send");
            assertThat(fields.get("channel")).isEqualTo("email");
            assertThat(fields.get("outcome")).isEqualTo("permanent-failure");
            assertThat(fields.get("reason")).isEqualTo("no-response");
        }

        @Test
        void should_retry_and_log_that_office_365_did_not_respond_when_it_times_out() {
            final UUID id = UUID.fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80");
            final HttpTimeoutException httpTimeoutException = new HttpTimeoutException("request timed out");
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new Office365Exception(null, "Office 365 did not answer in time", httpTimeoutException));

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
            final ILoggingEvent loggingEvent = onlyLineIn(policyLogs);
            final String message = loggingEvent.getFormattedMessage();
            assertThat(message).isEqualTo("Transient failure 'invoking the Office 365 send API' for notificationId"
                    + " '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' (the service did not respond) — will retry");
            final Map<String, Object> fields = fieldsOf(loggingEvent);
            assertThat(fields).hasSize(5);
            assertThat(fields.get("dependency")).isEqualTo("microsoft-365");
            assertThat(fields.get("operation")).isEqualTo("send");
            assertThat(fields.get("channel")).isEqualTo("email");
            assertThat(fields.get("outcome")).isEqualTo("transient-failure");
            assertThat(fields.get("reason")).isEqualTo("no-response");
        }
    }

    private static ILoggingEvent onlyLineIn(final CapturedLogs capturedLogs) {
        final List<ILoggingEvent> loggingEvents = capturedLogs.events();
        assertThat(loggingEvents).hasSize(1);
        return loggingEvents.getFirst();
    }

    private static Map<String, Object> fieldsOf(final ILoggingEvent loggingEvent) {
        final Map<String, Object> fields = new HashMap<>();
        final List<KeyValuePair> keyValuePairs = loggingEvent.getKeyValuePairs();
        for (final KeyValuePair keyValuePair : keyValuePairs) {
            fields.put(keyValuePair.key, keyValuePair.value);
        }
        return fields;
    }

    private ExecutionInfo sendEmailJob(final UUID notificationId, final String fileUri) {
        return sendEmailJob(notificationId, fileUri, null);
    }

    private ExecutionInfo sendEmailJob(final UUID notificationId, final String fileUri, final String materialUrl) {
        return ExecutionInfo.executionInfo()
                .withJobData(commandJobData(notificationId, fileUri, materialUrl))
                .withAssignedTaskName(SendEmailTask.TASK_NAME)
                .withAssignedTaskStartTime(ZonedDateTime.now(ZoneOffset.UTC))
                .withExecutionStatus(ExecutionStatus.STARTED)
                .build();
    }

    private ExecutionInfo sendEmailJobWithRetriesRemaining(
            final UUID notificationId, final String fileUri, final int retriesRemaining) {
        return sendEmailJobWithRetriesRemaining(notificationId, fileUri, null, retriesRemaining);
    }

    private ExecutionInfo sendEmailJobWithRetriesRemaining(
            final UUID notificationId, final String fileUri, final String materialUrl, final int retriesRemaining) {
        return ExecutionInfo.executionInfo()
                .withJobData(commandJobData(notificationId, fileUri, materialUrl))
                .withAssignedTaskName(SendEmailTask.TASK_NAME)
                .withAssignedTaskStartTime(ZonedDateTime.now(ZoneOffset.UTC))
                .withExecutionStatus(ExecutionStatus.STARTED)
                .withRetryAttemptsRemaining(retriesRemaining)
                .build();
    }

    private static JsonObject commandJobData(final UUID notificationId, final String fileUri, final String materialUrl) {
        final JsonObjectBuilder jobData = Json.createObjectBuilder()
                .add("notificationId", notificationId.toString())
                .add("templateId", UUID.randomUUID().toString())
                .add("sendToAddress", "user@example.com")
                .add("fileUri", fileUri);
        if (materialUrl != null) {
            jobData.add("materialUrl", materialUrl);
        }
        return jobData.build();
    }

    static Stream<Arguments> permanentMaterialFailures() {
        return Stream.of(
                arguments(MaterialAttachmentException.permanent("Material link is not on the Material service", null),
                        ATTACHMENT_BAD_LINK),
                arguments(MaterialAttachmentException.permanent(
                        "Material returned a document link that is not an https Azure Blob Storage link", null),
                        ATTACHMENT_BAD_LINK),
                arguments(MaterialAttachmentException.permanent("Material rejected the document request (HTTP 400)", 400),
                        ATTACHMENT_BAD_LINK),
                arguments(MaterialAttachmentException.permanent("Material refused access to the document (HTTP 401)", 401),
                        ATTACHMENT_ACCESS_REFUSED),
                arguments(MaterialAttachmentException.permanent("Material refused access to the document (HTTP 403)", 403),
                        ATTACHMENT_ACCESS_REFUSED),
                arguments(MaterialAttachmentException.permanent("Material has no such document (HTTP 404)", 404),
                        ATTACHMENT_NOT_FOUND),
                arguments(MaterialAttachmentException.permanent("Material rejected the document request (HTTP 413)", 413),
                        ATTACHMENT_TOO_LARGE),
                arguments(MaterialAttachmentException.permanent(
                        "The Material document exceeds the maximum attachment size of 15728640 bytes", 413),
                        ATTACHMENT_TOO_LARGE));
    }

    private static BlobStorageException blobStorageException(final int statusCode) {
        final HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(statusCode);
        return new BlobStorageException("blob storage error " + statusCode, response, null);
    }
}
