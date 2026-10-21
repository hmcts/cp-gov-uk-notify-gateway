package uk.gov.hmcts.cp.notification.task;

import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.models.BlobStorageException;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import uk.gov.hmcts.cp.notification.blob.AttachmentDownloader;
import uk.gov.hmcts.cp.notification.blob.PermanentBlobException;
import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentDownloader;
import uk.gov.hmcts.cp.notification.material.MaterialAttachmentException;
import uk.gov.hmcts.cp.notification.sender.Attachment;
import uk.gov.hmcts.cp.notification.sender.EmailSender;
import uk.gov.hmcts.cp.notification.sender.GovNotifyException;
import uk.gov.hmcts.cp.notification.sender.Office365Exception;
import uk.gov.hmcts.cp.notification.service.NotificationStatusService;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Captor
    private ArgumentCaptor<Attachment> attachmentCaptor;
    @Captor
    private ArgumentCaptor<ExecutionInfo> executionInfoCaptor;

    @BeforeEach
    void setUp() {
        task = new SendEmailTask(attachmentDownloader, materialAttachmentDownloader, emailSender, new TransientFailurePolicy(statusService, new Clock(), LEGACY_EMAIL_RETRY_DURATIONS),
                executionService, objectMapper, new CpTaskFactory(objectMapper, clock),
                LEGACY_EMAIL_RETRY_DURATIONS);
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
    class WhenSendingFails {

        @ParameterizedTest
        @MethodSource("uk.gov.hmcts.cp.notification.task.SendEmailTaskTest#permanentMaterialFailures")
        void should_mark_failed_with_the_failure_status_and_complete_without_retry_when_the_material_download_fails_permanently(
                final MaterialAttachmentException failure) {
            final UUID id = UUID.randomUUID();
            when(materialAttachmentDownloader.download(MATERIAL_URL, id)).thenThrow(failure);

            final ExecutionInfo result = task.execute(sendEmailJob(id, "", MATERIAL_URL));

            verify(statusService).markFailed(id, failure.getStatusCode(), failure.getMessage());
            verify(emailSender, never()).sendEmail(any(), any());
            verify(executionService, never()).executeWith(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.isShouldRetry()).isFalse();
        }

        @Test
        void should_retry_when_the_material_download_fails_retryably() {
            final UUID id = UUID.randomUUID();
            when(materialAttachmentDownloader.download(MATERIAL_URL, id)).thenThrow(
                    MaterialAttachmentException.retryable("Material did not return the document (HTTP 404)", 404));

            final ExecutionInfo result = task.execute(sendEmailJob(id, "", MATERIAL_URL));

            verify(statusService, never()).markFailed(any(), any(), any());
            verify(emailSender, never()).sendEmail(any(), any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
            assertThat(result.isShouldRetry()).isTrue();
        }

        @Test
        void should_mark_failed_with_the_last_status_when_the_material_download_keeps_failing_until_retries_are_exhausted() {
            final UUID id = UUID.randomUUID();
            when(materialAttachmentDownloader.download(MATERIAL_URL, id)).thenThrow(
                    MaterialAttachmentException.retryable("Material did not return the document (HTTP 403)", 403));

            final ExecutionInfo result = task.execute(sendEmailJobWithRetriesRemaining(id, "", MATERIAL_URL, 0));

            verify(statusService).markFailed(id, 403, "Material attachment download did not recover within the retry window");
            verify(emailSender, never()).sendEmail(any(), any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_mark_failed_with_no_status_when_a_timeout_keeps_the_material_download_failing_until_retries_are_exhausted() {
            final UUID id = UUID.randomUUID();
            when(materialAttachmentDownloader.download(MATERIAL_URL, id)).thenThrow(
                    MaterialAttachmentException.retryable("Material did not answer in time", null));

            task.execute(sendEmailJobWithRetriesRemaining(id, "", MATERIAL_URL, 0));

            verify(statusService).markFailed(id, null, "Material attachment download did not recover within the retry window");
        }

        @Test
        void should_mark_failed_and_complete_without_retry_when_blob_download_is_permanently_failed() {
            final UUID id = UUID.randomUUID();
            final String fileUri = "https://sa.blob.core.windows.net/mi-reportdata/missing.csv";
            when(attachmentDownloader.download(fileUri))
                    .thenThrow(new PermanentBlobException("blob 404"));

            final ExecutionInfo result = task.execute(sendEmailJob(id, fileUri));

            verify(statusService).markFailed(eq(id), any(), any());
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

            verify(statusService, never()).markFailed(any(), any(), any());
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

            verify(statusService).markFailed(eq(id), eq(503),
                    eq("Attachment download did not recover within the retry window"));
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

            verify(statusService).markFailed(eq(id), eq(400), eq("bad request"));
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

            verify(statusService).markFailed(id, status, "Office 365 responded with 'Invalid request payload'");
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

            verify(statusService).markFailed(id, null, "Office 365 request could not be built");
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.isShouldRetry()).isFalse();
        }

        @Test
        void should_retry_when_office365_fails_retryably() {
            final UUID id = UUID.randomUUID();
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new Office365Exception(null, "Office 365 could not be reached", null));

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            verify(statusService, never()).markFailed(any(), any(), any());
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

            verify(statusService).markFailed(id, 503, "Office 365 send did not recover within the retry window");
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void should_retry_when_send_fails_transiently() {
            final UUID id = UUID.randomUUID();
            when(emailSender.sendEmail(any(SendEmailCommand.class), any()))
                    .thenThrow(new GovNotifyException(500, "Gov.Notify unavailable", null));

            final ExecutionInfo result = task.execute(sendEmailJob(id, ""));

            verify(statusService, never()).markFailed(any(), any(), any());
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

            verify(statusService).markFailed(eq(id), eq(500),
                    eq("Gov.Notify send did not recover within the retry window"));
            verify(executionService, never()).executeWith(any());
            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
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

    static Stream<MaterialAttachmentException> permanentMaterialFailures() {
        return Stream.of(
                MaterialAttachmentException.permanent("Material link is not on the Material service", null),
                MaterialAttachmentException.permanent(
                        "Material returned a document link that is not an https Azure Blob Storage link", null),
                MaterialAttachmentException.permanent("Material rejected the document request (HTTP 400)", 400),
                MaterialAttachmentException.permanent("Material rejected the document request (HTTP 413)", 413),
                MaterialAttachmentException.permanent(
                        "The Material document exceeds the maximum attachment size of 15728640 bytes", 413));
    }

    private static BlobStorageException blobStorageException(final int statusCode) {
        final HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(statusCode);
        return new BlobStorageException("blob storage error " + statusCode, response, null);
    }
}
