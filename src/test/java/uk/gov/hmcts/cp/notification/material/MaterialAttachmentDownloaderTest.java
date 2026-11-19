package uk.gov.hmcts.cp.notification.material;

import static ch.qos.logback.classic.Level.INFO;
import static ch.qos.logback.classic.Level.WARN;
import static java.util.Optional.ofNullable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.integration.testdata.AttachmentFactory.MATERIAL_DOCUMENT_FILENAME;
import static uk.gov.hmcts.cp.notification.integration.testdata.AttachmentFactory.aMaterialDocumentAttachment;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialDocumentLinkFactory.SAS_HOST;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialDocumentLinkFactory.SAS_LINK;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialDocumentLinkFactory.SAS_QUERY;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialDocumentLinkFactory.aMaterialDocumentLink;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialSettingsFactory.MATERIAL_BASE_URL;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialSettingsFactory.SYSTEM_USER_ID;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.MATERIAL_ID;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.MATERIAL_URL;
import static uk.gov.hmcts.cp.notification.logging.CapturedLogs.captureLogsOf;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Channel.LETTER;
import static uk.gov.hmcts.cp.notification.logging.Dependency.BLOB_STORAGE;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.KeyValuePair;

import uk.gov.hmcts.cp.notification.logging.CapturedLogs;
import uk.gov.hmcts.cp.notification.sender.Attachment;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class MaterialAttachmentDownloaderTest {
    private static final URI RESOLVED_MATERIAL_URI = URI.create(
            MATERIAL_BASE_URL + "/query/api/rest/material/material/" + MATERIAL_ID + "?stream=true&requestPdf=true");
    private static final URI SAS_URI = URI.create(SAS_LINK);
    private static final byte[] DOCUMENT = aMaterialDocumentAttachment().content();

    @Mock
    private MaterialUrlResolver materialUrlResolver;
    @Mock
    private MaterialClient materialClient;
    @Mock
    private SasLinkValidator sasLinkValidator;
    @Mock
    private SasDocumentFetcher sasDocumentFetcher;

    private final UUID notificationId = UUID.randomUUID();

    private MaterialAttachmentDownloader materialAttachmentDownloader;
    private CapturedLogs capturedLogs;

    @BeforeEach
    void createDownloaderAndCaptureItsLogs() {
        materialAttachmentDownloader = new MaterialAttachmentDownloader(
                materialUrlResolver, materialClient, sasLinkValidator, sasDocumentFetcher);
        capturedLogs = captureLogsOf(MaterialAttachmentDownloader.class);
    }

    @AfterEach
    void stopCapturingLogs() {
        capturedLogs.close();
    }

    @Test
    void should_resolve_query_check_and_fetch_in_order_returning_the_document_with_the_filename_material_reported() {
        givenEveryStageSucceeds();

        final Attachment attachment = materialAttachmentDownloader.download(MATERIAL_URL, notificationId, EMAIL);

        assertThat(attachment.content()).isEqualTo(DOCUMENT);
        assertThat(attachment.filename()).isEqualTo(MATERIAL_DOCUMENT_FILENAME);
        final InOrder inOrder = inOrder(materialUrlResolver, materialClient, sasLinkValidator, sasDocumentFetcher);
        inOrder.verify(materialUrlResolver).resolve(MATERIAL_URL);
        inOrder.verify(materialClient).requestDocumentLink(RESOLVED_MATERIAL_URI, notificationId);
        inOrder.verify(sasLinkValidator).validate(SAS_LINK);
        inOrder.verify(sasDocumentFetcher).fetch(SAS_URI);
    }

    @Test
    void should_log_each_successful_call_with_its_dependency_and_the_notification_channel() {
        givenEveryStageSucceeds();

        materialAttachmentDownloader.download(MATERIAL_URL, notificationId, LETTER);

        final List<ILoggingEvent> loggingEvents = capturedLogs.events();
        assertThat(loggingEvents).hasSize(2);
        final ILoggingEvent materialLoggingEvent = loggingEvents.get(0);
        assertThat(materialLoggingEvent.getLevel()).isEqualTo(INFO);
        assertThat(materialLoggingEvent.getFormattedMessage()).matches(
                "Obtained the Material document link for notification '" + notificationId + "' in '\\d+' ms");
        final Map<String, Object> materialFields = fieldsOf(materialLoggingEvent);
        assertThat(materialFields).hasSize(5);
        assertThat(materialFields.get("dependency")).isEqualTo(MATERIAL.getFieldValue());
        assertThat(materialFields.get("operation")).isEqualTo("download");
        assertThat(materialFields.get("channel")).isEqualTo("letter");
        assertThat(materialFields.get("outcome")).isEqualTo("succeeded");
        assertThat(materialFields.get("statusCode")).isEqualTo(200);
        final ILoggingEvent downloadLoggingEvent = loggingEvents.get(1);
        assertThat(downloadLoggingEvent.getLevel()).isEqualTo(INFO);
        assertThat(downloadLoggingEvent.getFormattedMessage()).matches(
                "Downloaded the Material document for notification '" + notificationId + "' from '" + SAS_HOST
                        + "' \\('" + DOCUMENT.length + "' bytes\\) in '\\d+' ms");
        final Map<String, Object> downloadFields = fieldsOf(downloadLoggingEvent);
        assertThat(downloadFields).hasSize(5);
        assertThat(downloadFields.get("dependency")).isEqualTo(BLOB_STORAGE.getFieldValue());
        assertThat(downloadFields.get("operation")).isEqualTo("download");
        assertThat(downloadFields.get("channel")).isEqualTo("letter");
        assertThat(downloadFields.get("outcome")).isEqualTo("succeeded");
        assertThat(downloadFields.get("statusCode")).isEqualTo(200);
    }

    @Test
    void should_rethrow_a_refused_link_after_logging_its_message_without_the_call_fields_or_calling_material() {
        final MaterialAttachmentException refused = MaterialAttachmentException.permanent(
                MATERIAL, "Material link is not on the Material service", null);
        when(materialUrlResolver.resolve(MATERIAL_URL)).thenThrow(refused);

        assertThatThrownBy(() -> materialAttachmentDownloader.download(MATERIAL_URL, notificationId, EMAIL))
                .isSameAs(refused);

        verifyNoInteractions(materialClient, sasLinkValidator, sasDocumentFetcher);
        final ILoggingEvent loggingEvent = onlyLoggingEvent();
        assertThat(loggingEvent.getLevel()).isEqualTo(WARN);
        assertThat(loggingEvent.getFormattedMessage()).matches(
                "Could not obtain the Material document link for notification '" + notificationId
                        + "' after '\\d+' ms: 'Material link is not on the Material service'");
        assertThat(fieldsOf(loggingEvent)).isEmpty();
    }

    @Test
    void should_rethrow_a_material_failure_after_logging_its_message() {
        final MaterialAttachmentException unavailable = MaterialAttachmentException.retryable(
                MATERIAL, "Material did not return the document (HTTP 503)", 503);
        when(materialUrlResolver.resolve(MATERIAL_URL)).thenReturn(RESOLVED_MATERIAL_URI);
        when(materialClient.requestDocumentLink(RESOLVED_MATERIAL_URI, notificationId)).thenThrow(unavailable);

        assertThatThrownBy(() -> materialAttachmentDownloader.download(MATERIAL_URL, notificationId, EMAIL))
                .isSameAs(unavailable);

        verifyNoInteractions(sasLinkValidator, sasDocumentFetcher);
        assertThat(onlyLoggingEvent().getFormattedMessage())
                .endsWith(": 'Material did not return the document (HTTP 503)'");
    }

    @Test
    void should_rethrow_a_rejected_document_link_after_logging_its_message_without_downloading() {
        final MaterialAttachmentException rejected = MaterialAttachmentException.permanent(
                MATERIAL, "Material returned a document link that is not an https Azure Blob Storage link", null);
        givenMaterialAnswers();
        when(sasLinkValidator.validate(SAS_LINK)).thenThrow(rejected);

        assertThatThrownBy(() -> materialAttachmentDownloader.download(MATERIAL_URL, notificationId, EMAIL))
                .isSameAs(rejected);

        verifyNoInteractions(sasDocumentFetcher);
        final List<ILoggingEvent> loggingEvents = capturedLogs.events();
        assertThat(loggingEvents).hasSize(2);
        final ILoggingEvent failureLoggingEvent = loggingEvents.get(1);
        assertThat(failureLoggingEvent.getFormattedMessage()).matches(
                "Could not download the Material document for notification '" + notificationId + "' after '\\d+' ms: "
                        + "'Material returned a document link that is not an https Azure Blob Storage link'");
        assertThat(fieldsOf(failureLoggingEvent)).isEmpty();
    }

    @Test
    void should_rethrow_a_download_failure_after_logging_its_message() {
        final MaterialAttachmentException unavailable = MaterialAttachmentException.retryable(
                BLOB_STORAGE, "The Material document could not be downloaded (HTTP 503)", 503);
        givenMaterialAnswers();
        when(sasLinkValidator.validate(SAS_LINK)).thenReturn(SAS_URI);
        when(sasDocumentFetcher.fetch(SAS_URI)).thenThrow(unavailable);

        assertThatThrownBy(() -> materialAttachmentDownloader.download(MATERIAL_URL, notificationId, EMAIL))
                .isSameAs(unavailable);

        final List<ILoggingEvent> loggingEvents = capturedLogs.events();
        assertThat(loggingEvents).hasSize(2);
        final ILoggingEvent failureLoggingEvent = loggingEvents.get(1);
        assertThat(failureLoggingEvent.getFormattedMessage())
                .endsWith(": 'The Material document could not be downloaded (HTTP 503)'");
    }

    @Test
    void should_log_only_the_failure_message_never_the_throwable_or_its_cause() {
        givenMaterialAnswers();
        when(sasLinkValidator.validate(SAS_LINK)).thenReturn(SAS_URI);
        when(sasDocumentFetcher.fetch(SAS_URI)).thenThrow(MaterialAttachmentException.retryable(
                BLOB_STORAGE, "The Material document could not be downloaded", null,
                new IOException("connection reset by " + SAS_HOST + " for " + SAS_LINK)));

        assertThatThrownBy(() -> materialAttachmentDownloader.download(MATERIAL_URL, notificationId, EMAIL))
                .isInstanceOf(MaterialAttachmentException.class);

        assertThat(capturedLogs.events()).allSatisfy(loggingEvent -> {
            assertThat(loggingEvent.getThrowableProxy()).isNull();
            assertThat(loggingEvent.getFormattedMessage()).doesNotContain("sig=").doesNotContain("connection reset");
        });
    }

    @Test
    void should_never_log_the_sas_query_string_the_material_host_the_base_url_or_the_system_user() {
        givenEveryStageSucceeds();

        materialAttachmentDownloader.download(MATERIAL_URL, notificationId, EMAIL);

        final String materialHost = URI.create(MATERIAL_BASE_URL).getHost();
        assertThat(capturedLogs.events()).allSatisfy(loggingEvent -> assertThat(loggingEvent.getFormattedMessage())
                .doesNotContain("sig=")
                .doesNotContain(SAS_QUERY)
                .doesNotContain(MATERIAL_BASE_URL)
                .doesNotContain(materialHost)
                .doesNotContain(SYSTEM_USER_ID.toString()));
    }

    private void givenMaterialAnswers() {
        when(materialUrlResolver.resolve(MATERIAL_URL)).thenReturn(RESOLVED_MATERIAL_URI);
        when(materialClient.requestDocumentLink(RESOLVED_MATERIAL_URI, notificationId))
                .thenReturn(aMaterialDocumentLink());
    }

    private void givenEveryStageSucceeds() {
        givenMaterialAnswers();
        when(sasLinkValidator.validate(SAS_LINK)).thenReturn(SAS_URI);
        when(sasDocumentFetcher.fetch(SAS_URI)).thenReturn(DOCUMENT);
    }

    private ILoggingEvent onlyLoggingEvent() {
        final List<ILoggingEvent> loggingEvents = capturedLogs.events();
        assertThat(loggingEvents).hasSize(1);
        return loggingEvents.getFirst();
    }

    private static Map<String, Object> fieldsOf(final ILoggingEvent loggingEvent) {
        final Map<String, Object> fields = new HashMap<>();
        final Optional<List<KeyValuePair>> possibleKeyValuePairs = ofNullable(loggingEvent.getKeyValuePairs());
        final List<KeyValuePair> keyValuePairs = possibleKeyValuePairs.orElse(List.of());
        for (final KeyValuePair keyValuePair : keyValuePairs) {
            fields.put(keyValuePair.key, keyValuePair.value);
        }
        return fields;
    }
}
