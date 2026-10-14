package uk.gov.hmcts.cp.notification.material;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.hmcts.cp.notification.logging.CapturedLogs;
import uk.gov.hmcts.cp.notification.sender.Attachment;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
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

@ExtendWith(MockitoExtension.class)
class MaterialAttachmentDownloaderTest {
    private static final URI RESOLVED_MATERIAL_URI = URI.create(
            MATERIAL_BASE_URL + "/query/api/rest/material/material/" + MATERIAL_ID + "?stream=true&requestPdf=true");
    private static final URI SAS_URI = URI.create(SAS_LINK);
    private static final byte[] DOCUMENT = aMaterialDocumentAttachment().content();

    @Mock
    private MaterialUrlResolver urlResolver;
    @Mock
    private MaterialClient materialClient;
    @Mock
    private SasLinkValidator sasLinkValidator;
    @Mock
    private SasDocumentFetcher sasDocumentFetcher;

    private final UUID notificationId = UUID.randomUUID();

    private MaterialAttachmentDownloader downloader;
    private CapturedLogs logs;

    @BeforeEach
    void setUp() {
        downloader = new MaterialAttachmentDownloader(urlResolver, materialClient, sasLinkValidator, sasDocumentFetcher);
        logs = captureLogsOf(MaterialAttachmentDownloader.class);
        when(urlResolver.resolve(MATERIAL_URL)).thenReturn(RESOLVED_MATERIAL_URI);
        when(materialClient.requestDocumentLink(RESOLVED_MATERIAL_URI, notificationId)).thenReturn(aMaterialDocumentLink());
        when(sasLinkValidator.validate(SAS_LINK)).thenReturn(SAS_URI);
        when(sasDocumentFetcher.fetch(SAS_URI)).thenReturn(DOCUMENT);
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    @Test
    void should_resolve_query_check_and_fetch_in_order_returning_the_document_with_the_filename_material_reported() {
        final Attachment attachment = downloader.download(MATERIAL_URL, notificationId);

        assertThat(attachment.content()).isEqualTo(DOCUMENT);
        assertThat(attachment.filename()).isEqualTo(MATERIAL_DOCUMENT_FILENAME);
        final InOrder order = inOrder(urlResolver, materialClient, sasLinkValidator, sasDocumentFetcher);
        order.verify(urlResolver).resolve(MATERIAL_URL);
        order.verify(materialClient).requestDocumentLink(RESOLVED_MATERIAL_URI, notificationId);
        order.verify(sasLinkValidator).validate(SAS_LINK);
        order.verify(sasDocumentFetcher).fetch(SAS_URI);
    }

    @Test
    void should_log_one_line_per_stage() {
        downloader.download(MATERIAL_URL, notificationId);

        final List<ILoggingEvent> events = logs.events();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getFormattedMessage())
                .startsWith("Material attachment stage=material")
                .contains("notificationId=" + notificationId)
                .contains("status=200")
                .contains("elapsedMs=")
                .doesNotContain("host=");
        assertThat(events.get(1).getFormattedMessage())
                .startsWith("Material attachment stage=sas")
                .contains("notificationId=" + notificationId)
                .contains("host=" + SAS_HOST)
                .contains("status=200")
                .contains("bytes=" + DOCUMENT.length)
                .contains("elapsedMs=");
    }

    @Test
    void should_never_log_the_sas_query_string_the_material_host_the_base_url_or_the_system_user() {
        downloader.download(MATERIAL_URL, notificationId);

        assertThat(logs.events()).allSatisfy(event -> assertThat(
                event.getFormattedMessage())
                .doesNotContain("sig=")
                .doesNotContain(SAS_QUERY)
                .doesNotContain(MATERIAL_BASE_URL)
                .doesNotContain(URI.create(MATERIAL_BASE_URL).getHost())
                .doesNotContain(SYSTEM_USER_ID.toString()));
    }
}
