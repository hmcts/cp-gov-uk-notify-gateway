package uk.gov.hmcts.cp.notification.sender;

import static ch.qos.logback.classic.Level.INFO;
import static java.util.UUID.fromString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.integration.testdata.AttachmentFactory.aFileAttachment;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommand;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailRequestFactory.aSendEmailRequest;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendResultFactory.aSendResult;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MICROSOFT_365;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.KeyValuePair;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.logging.CapturedLogs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class EmailSenderTest {
    @Mock
    private SendEmailRequestMapper sendEmailRequestMapper;
    @Mock
    private EmailClientFactory emailClientFactory;
    @Mock
    private EmailClient emailClient;

    private EmailSender emailSender;

    @BeforeEach
    void createEmailSenderWithMockedClients() {
        emailSender = new EmailSender(sendEmailRequestMapper, emailClientFactory);
    }

    @Test
    void should_send_the_request_through_the_client_that_can_take_it_and_log_the_send() {
        final SendEmailCommand command =
                aSendEmailCommand().notificationId(fromString("0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80")).build();
        final Attachment attachment = aFileAttachment();
        final SendEmailRequest request = aSendEmailRequest().build();
        final SendResult sendResult = aSendResult().reference("ref-1").build();
        when(sendEmailRequestMapper.requestFor(command, attachment)).thenReturn(request);
        when(emailClientFactory.selectFor(request)).thenReturn(emailClient);
        when(emailClient.send(request)).thenReturn(sendResult);
        when(emailClient.dependency()).thenReturn(MICROSOFT_365);

        final SendResult result;
        final ILoggingEvent loggingEvent;
        try (CapturedLogs capturedLogs = captureLogsOf(EmailSender.class)) {
            result = emailSender.sendEmail(command, attachment);
            loggingEvent = onlyEventIn(capturedLogs);
        }

        assertThat(result).isSameAs(sendResult);
        assertThat(loggingEvent.getLevel()).isEqualTo(INFO);
        final String message = loggingEvent.getFormattedMessage();
        assertThat(message).isEqualTo(
                "Sent notification '0f8c2b6e-1d4a-4c57-9b1e-3a2d5f6e7c80' via 'microsoft-365' (reference 'ref-1')");
        final Map<String, Object> fields = fieldsOf(loggingEvent);
        assertThat(fields).hasSize(4);
        assertThat(fields.get("dependency")).isEqualTo("microsoft-365");
        assertThat(fields.get("operation")).isEqualTo("send");
        assertThat(fields.get("channel")).isEqualTo("email");
        assertThat(fields.get("outcome")).isEqualTo("succeeded");
    }

    private ILoggingEvent onlyEventIn(final CapturedLogs capturedLogs) {
        final List<ILoggingEvent> loggingEvents = capturedLogs.events();
        assertThat(loggingEvents).hasSize(1);
        return loggingEvents.getFirst();
    }

    private Map<String, Object> fieldsOf(final ILoggingEvent loggingEvent) {
        final Map<String, Object> fields = new HashMap<>();
        final List<KeyValuePair> keyValuePairs = loggingEvent.getKeyValuePairs();
        for (final KeyValuePair keyValuePair : keyValuePairs) {
            fields.put(keyValuePair.key, keyValuePair.value);
        }
        return fields;
    }
}
