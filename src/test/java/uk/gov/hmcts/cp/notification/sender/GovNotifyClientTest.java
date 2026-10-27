package uk.gov.hmcts.cp.notification.sender;

import org.json.JSONObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.service.notify.LetterResponse;
import uk.gov.service.notify.Notification;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.NotificationClientException;
import uk.gov.service.notify.SendEmailResponse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailRequestFactory.aSendEmailRequest;

@ExtendWith(MockitoExtension.class)
class GovNotifyClientTest {

    @Mock
    private NotificationClient notificationClient;

    @InjectMocks
    private GovNotifyClient govNotifyClient;

    @Captor
    private ArgumentCaptor<Map<String, Object>> personalisationCaptor;

    @Captor
    private ArgumentCaptor<InputStream> documentCaptor;

    @Nested
    class Sending {

        @Test
        void send_forwards_template_recipient_reference_and_reply_to_and_returns_the_external_id() throws Exception {
            final UUID notificationId = UUID.randomUUID();
            final UUID templateId = UUID.randomUUID();
            final UUID replyToId = UUID.randomUUID();
            final UUID externalId = UUID.randomUUID();
            final SendEmailResponse response = mock(SendEmailResponse.class);
            when(response.getNotificationId()).thenReturn(externalId);
            when(response.getFromEmail()).thenReturn(Optional.empty());
            when(notificationClient.sendEmail(any(), any(), any(), any(), any())).thenReturn(response);

            final SendResult result = govNotifyClient.send(aSendEmailRequest()
                    .notificationId(notificationId)
                    .templateId(templateId)
                    .emailAddress("user@example.com")
                    .attachment(null)
                    .replyToAddressId(replyToId)
                    .build());

            assertThat(result.reference()).isEqualTo(externalId.toString());
            verify(notificationClient).sendEmail(
                    eq(templateId.toString()), eq("user@example.com"), any(), eq(notificationId.toString()),
                    eq(replyToId.toString()));
        }

        @Test
        void send_captures_the_subject_body_and_reply_to_from_the_provider_response() throws Exception {
            final SendEmailResponse response = mock(SendEmailResponse.class);
            when(response.getNotificationId()).thenReturn(UUID.randomUUID());
            when(response.getSubject()).thenReturn("Your NCES extract");
            when(response.getBody()).thenReturn("Please find your report attached.");
            when(response.getFromEmail()).thenReturn(Optional.of("noreply@justice.gov.uk"));
            when(notificationClient.sendEmail(any(), any(), any(), any(), any())).thenReturn(response);

            final SendResult result = govNotifyClient.send(aSendEmailRequest().attachment(null).build());

            assertThat(result.emailSubject()).isEqualTo("Your NCES extract");
            assertThat(result.emailBody()).isEqualTo("Please find your report attached.");
            assertThat(result.replyToAddress()).isEqualTo("noreply@justice.gov.uk");
        }

        @Test
        void send_passes_an_empty_reply_to_when_none_is_supplied() throws Exception {
            stubSendReturningExternalId();

            govNotifyClient.send(aSendEmailRequest().attachment(null).replyToAddressId(null).build());

            verify(notificationClient).sendEmail(any(), any(), any(), any(), eq(""));
        }

        @Test
        void send_merges_the_command_personalisation_and_adds_no_material_url_without_an_attachment() throws Exception {
            stubSendReturningExternalId();

            govNotifyClient.send(aSendEmailRequest()
                    .attachment(null)
                    .personalisation(Map.of("recipientName", "Alice"))
                    .build());

            verify(notificationClient).sendEmail(any(), any(), personalisationCaptor.capture(), any(), any());
            final Map<String, Object> personalisation = personalisationCaptor.getValue();
            assertThat(personalisation).containsEntry("recipientName", "Alice");
            assertThat(personalisation).doesNotContainKey("material_url");
        }

        @Test
        void send_encodes_the_attachment_under_material_url_with_its_filename() throws Exception {
            stubSendReturningExternalId();

            govNotifyClient.send(aSendEmailRequest()
                    .attachment("a,b\n1,2".getBytes(StandardCharsets.UTF_8))
                    .attachmentFilename("report.csv")
                    .build());

            final JSONObject document = capturedMaterialUrl();
            assertThat(document.has("file")).isTrue();
            assertThat(document.getString("filename")).isEqualTo("report.csv");
        }

        @Test
        void send_passes_the_attachment_filename_through_for_any_extension() throws Exception {
            stubSendReturningExternalId();

            govNotifyClient.send(aSendEmailRequest()
                    .attachment("report-bytes".getBytes(StandardCharsets.UTF_8))
                    .attachmentFilename("report.pdf")
                    .build());

            assertThat(capturedMaterialUrl().getString("filename")).isEqualTo("report.pdf");
        }

        @Test
        void send_wraps_a_notification_client_exception_as_a_gov_notify_exception() throws Exception {
            final NotificationClientException clientException = mock(NotificationClientException.class);
            when(clientException.getHttpResult()).thenReturn(400);
            when(clientException.getMessage()).thenReturn("bad request");
            when(notificationClient.sendEmail(any(), any(), any(), any(), any())).thenThrow(clientException);

            assertThatThrownBy(() -> govNotifyClient.send(aSendEmailRequest().attachment(null).build()))
                    .isInstanceOf(GovNotifyException.class)
                    .hasMessage("bad request")
                    .extracting(e -> ((GovNotifyException) e).getHttpStatus())
                    .isEqualTo(400);
        }
    }

    @Nested
    class SendingLetters {
        private static final byte[] PDF = "%PDF-1.4 letter".getBytes(StandardCharsets.UTF_8);

        @Test
        void send_precompiled_letter_sends_a_first_class_letter_with_first_postage_and_returns_the_external_id()
                throws Exception {
            final UUID notificationId = UUID.randomUUID();
            final UUID externalId = UUID.randomUUID();
            final LetterResponse response = mock(LetterResponse.class);
            when(response.getNotificationId()).thenReturn(externalId);
            when(notificationClient.sendPrecompiledLetterWithInputStream(eq(notificationId.toString()), any(), eq("first")))
                    .thenReturn(response);

            final String reference = govNotifyClient.sendPrecompiledLetter(notificationId, PDF, true);

            assertThat(reference).isEqualTo(externalId.toString());
            verify(notificationClient).sendPrecompiledLetterWithInputStream(
                    eq(notificationId.toString()), documentCaptor.capture(), eq("first"));
            assertThat(documentCaptor.getValue().readAllBytes()).isEqualTo(PDF);
        }

        @Test
        void send_precompiled_letter_sends_any_other_letter_without_postage() throws Exception {
            final UUID notificationId = UUID.randomUUID();
            final LetterResponse response = mock(LetterResponse.class);
            when(response.getNotificationId()).thenReturn(UUID.randomUUID());
            when(notificationClient.sendPrecompiledLetterWithInputStream(eq(notificationId.toString()), any()))
                    .thenReturn(response);

            govNotifyClient.sendPrecompiledLetter(notificationId, PDF, false);

            verify(notificationClient).sendPrecompiledLetterWithInputStream(
                    eq(notificationId.toString()), documentCaptor.capture());
            assertThat(documentCaptor.getValue().readAllBytes()).isEqualTo(PDF);
            verify(notificationClient, never()).sendPrecompiledLetterWithInputStream(any(), any(), any());
        }

        @Test
        void send_precompiled_letter_wraps_a_notification_client_exception_as_a_gov_notify_exception() throws Exception {
            final NotificationClientException clientException = mock(NotificationClientException.class);
            when(clientException.getHttpResult()).thenReturn(400);
            when(clientException.getMessage()).thenReturn("precompiledPDF must be a valid PDF file");
            when(notificationClient.sendPrecompiledLetterWithInputStream(any(), any())).thenThrow(clientException);

            assertThatThrownBy(() -> govNotifyClient.sendPrecompiledLetter(UUID.randomUUID(), PDF, false))
                    .isInstanceOf(GovNotifyException.class)
                    .hasMessage("precompiledPDF must be a valid PDF file")
                    .extracting(e -> ((GovNotifyException) e).getHttpStatus())
                    .isEqualTo(400);
        }
    }

    @Nested
    class CheckingStatus {

        @Test
        void check_status_maps_the_provider_status_to_the_notification_status() throws Exception {
            final Notification notification = mock(Notification.class);
            when(notification.getStatus()).thenReturn("permanent-failure");
            when(notificationClient.getNotificationById("ref-1")).thenReturn(notification);

            assertThat(govNotifyClient.checkStatus("ref-1")).isEqualTo(NotificationStatus.PERMANENT_FAILURE);
        }

        @Test
        void check_status_wraps_a_notification_client_exception_as_a_gov_notify_exception() throws Exception {
            final NotificationClientException clientException = mock(NotificationClientException.class);
            when(clientException.getHttpResult()).thenReturn(500);
            when(clientException.getMessage()).thenReturn("unavailable");
            when(notificationClient.getNotificationById("ref-1")).thenThrow(clientException);

            assertThatThrownBy(() -> govNotifyClient.checkStatus("ref-1"))
                    .isInstanceOf(GovNotifyException.class)
                    .extracting(e -> ((GovNotifyException) e).getHttpStatus())
                    .isEqualTo(500);
        }
    }

    private void stubSendReturningExternalId() throws Exception {
        final SendEmailResponse response = mock(SendEmailResponse.class);
        when(response.getNotificationId()).thenReturn(UUID.randomUUID());
        when(response.getFromEmail()).thenReturn(Optional.empty());
        when(notificationClient.sendEmail(any(), any(), any(), any(), any())).thenReturn(response);
    }

    private JSONObject capturedMaterialUrl() throws Exception {
        verify(notificationClient).sendEmail(any(), any(), personalisationCaptor.capture(), any(), any());
        return (JSONObject) personalisationCaptor.getValue().get("material_url");
    }
}
