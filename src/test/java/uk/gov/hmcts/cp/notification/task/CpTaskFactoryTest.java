package uk.gov.hmcts.cp.notification.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommand;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendResultFactory.aSendResult;

@ExtendWith(MockitoExtension.class)
class CpTaskFactoryTest {
    private static final ZonedDateTime FIXED_START_TIME =
            ZonedDateTime.ofInstant(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private Clock clock;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private CpTaskFactory taskFactory;

    @BeforeEach
    void setUp() {
        when(clock.zonedDateTime()).thenReturn(FIXED_START_TIME);
        taskFactory = new CpTaskFactory(objectMapper, clock);
    }

    @Test
    void builds_a_send_email_job_carrying_the_command_and_the_clock_start_time() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo job = taskFactory.createSendEmailJob(aSendEmailCommand().notificationId(id).build());

        assertThat(job.getAssignedTaskName()).isEqualTo(SendEmailTask.TASK_NAME);
        assertThat(job.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        assertThat(job.getJobData().toString()).contains(id.toString());
        assertThat(job.getAssignedTaskStartTime()).isEqualTo(FIXED_START_TIME);
    }

    @Test
    void builds_a_check_status_job_carrying_the_notification_id_reference_and_captured_email_details() {
        final UUID id = UUID.randomUUID();
        final String reference = "1490dab7-2b48-4a9a-9f8a-2f0d0e2e6b11";

        final ExecutionInfo job = taskFactory.createCheckStatusJob(
                aSendEmailCommand().notificationId(id).build(),
                aSendResult()
                        .reference(reference)
                        .emailSubject("Your NCES extract")
                        .emailBody("Please find your report attached.")
                        .replyToAddress("noreply@justice.gov.uk")
                        .build());

        assertThat(job.getAssignedTaskName()).isEqualTo(CheckEmailStatusTask.TASK_NAME);
        assertThat(job.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        assertThat(job.getJobData().toString())
                .contains(id.toString())
                .contains(reference)
                .contains("Your NCES extract")
                .contains("Please find your report attached.")
                .contains("noreply@justice.gov.uk");
    }

    @Test
    void builds_a_record_sent_job_carrying_the_notification_id_and_the_email_details() {
        final UUID id = UUID.randomUUID();

        final ExecutionInfo job = taskFactory.createRecordSentJob(
                aSendEmailCommand().notificationId(id).build(),
                aSendResult()
                        .emailSubject("Your document is ready")
                        .emailBody("Please find the document attached.")
                        .replyToAddress("NOREPLY@noreply.com")
                        .sentOnSubmit(true)
                        .build());

        assertThat(job.getAssignedTaskName()).isEqualTo(RecordSentTask.TASK_NAME);
        assertThat(job.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        assertThat(job.getAssignedTaskStartTime()).isEqualTo(FIXED_START_TIME);
        assertThat(job.getJobData().getString(RecordSentTask.KEY_NOTIFICATION_ID)).isEqualTo(id.toString());
        assertThat(job.getJobData().getString(RecordSentTask.KEY_EMAIL_SUBJECT)).isEqualTo("Your document is ready");
        assertThat(job.getJobData().getString(RecordSentTask.KEY_EMAIL_BODY)).isEqualTo("Please find the document attached.");
        assertThat(job.getJobData().getString(RecordSentTask.KEY_REPLY_TO_ADDRESS)).isEqualTo("NOREPLY@noreply.com");
    }
}
