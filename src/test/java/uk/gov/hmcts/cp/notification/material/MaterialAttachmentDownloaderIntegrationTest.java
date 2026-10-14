package uk.gov.hmcts.cp.notification.material;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.notification.integration.config.WireMockSasLinkValidator;
import uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub;
import uk.gov.hmcts.cp.notification.integration.stubs.support.WireMockSupport;
import uk.gov.hmcts.cp.notification.sender.Attachment;

import java.net.http.HttpClient;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.cp.notification.integration.Fixtures.loadBytes;
import static uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub.aMaterialService;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialSettingsFactory.SYSTEM_USER_ID;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialSettingsFactory.aMaterialSettings;

class MaterialAttachmentDownloaderIntegrationTest {
    private static final String FILENAME = "d20.pdf";
    private static final long MAX_ATTACHMENT_BYTES = 15_728_640L;
    private static final byte[] DOCUMENT = loadBytes("fixtures/attachments/material-document.pdf");

    private final MaterialServiceStub material = aMaterialService();
    private final UUID notificationId = UUID.randomUUID();
    private final String materialId = UUID.randomUUID().toString();

    @BeforeEach
    void resetStubs() {
        WireMockSupport.reset();
    }

    @Nested
    class WhenMaterialServesTheDocument {

        @Test
        void downloads_the_document_for_a_material_client_library_link_rewritten_onto_the_material_service() {
            material.documentWillBeServed(materialId, FILENAME, DOCUMENT);

            final Attachment attachment = downloaderWithMaxBytes(MAX_ATTACHMENT_BYTES)
                    .download(material.materialClientLinkFor(materialId), notificationId);

            assertThat(attachment.content()).isEqualTo(DOCUMENT);
            assertThat(attachment.filename()).isEqualTo(FILENAME);
            material.documentWasRequestedFor(materialId, notificationId, SYSTEM_USER_ID)
                    .documentWasDownloadedWithoutCredentials(materialId, FILENAME);
        }

        @Test
        void downloads_the_document_for_a_link_already_on_the_material_service_using_it_as_given() {
            material.documentWillBeServed(materialId, FILENAME, DOCUMENT);

            final Attachment attachment = downloaderWithMaxBytes(MAX_ATTACHMENT_BYTES)
                    .download(material.materialServiceLinkFor(materialId), notificationId);

            assertThat(attachment.content()).isEqualTo(DOCUMENT);
            material.documentWasRequestedFor(materialId, notificationId, SYSTEM_USER_ID);
        }
    }

    @Nested
    class WhenTheDocumentLinkMustNotBeFollowed {

        @Test
        void does_not_follow_a_redirect_from_the_document_link() {
            material.documentLinkWillRedirect(materialId, FILENAME);

            assertThatThrownBy(() -> downloaderWithMaxBytes(MAX_ATTACHMENT_BYTES)
                    .download(material.materialClientLinkFor(materialId), notificationId))
                    .isInstanceOf(MaterialAttachmentException.class)
                    .hasFieldOrPropertyWithValue("statusCode", 302);

            material.redirectWasNotFollowed();
        }
    }

    @Nested
    class WhenTheDocumentIsTooLarge {

        @Test
        void refuses_a_document_whose_declared_length_exceeds_the_cap_without_reading_it() {
            material.documentWillBeServed(materialId, FILENAME, DOCUMENT);

            assertThatThrownBy(() -> downloaderWithMaxBytes(DOCUMENT.length - 1L)
                    .download(material.materialClientLinkFor(materialId), notificationId))
                    .isInstanceOf(MaterialAttachmentException.class)
                    .hasFieldOrPropertyWithValue("statusCode", 413)
                    .hasMessage("The Material document declares " + DOCUMENT.length
                            + " bytes, exceeding the maximum attachment size of " + (DOCUMENT.length - 1) + " bytes");
        }

        @Test
        void stops_reading_a_document_that_passes_the_cap_without_declaring_its_length() {
            material.documentWillBeServedWithoutDeclaringItsLength(materialId, FILENAME, DOCUMENT);

            assertThatThrownBy(() -> downloaderWithMaxBytes(DOCUMENT.length - 1L)
                    .download(material.materialClientLinkFor(materialId), notificationId))
                    .isInstanceOf(MaterialAttachmentException.class)
                    .hasFieldOrPropertyWithValue("statusCode", 413)
                    .hasMessage("The Material document passed the maximum attachment size of "
                            + (DOCUMENT.length - 1) + " bytes while downloading");
        }
    }

    private static MaterialAttachmentDownloader downloaderWithMaxBytes(final long maxBytes) {
        final MaterialSettings settings = aMaterialSettings(MaterialServiceStub.baseUrl());
        final HttpClient sharedClient = new MaterialConfig().materialHttpClient(settings);
        return new MaterialAttachmentDownloader(
                new MaterialUrlResolver(settings),
                new MaterialClient(sharedClient, settings),
                new WireMockSasLinkValidator(),
                new SasDocumentFetcher(sharedClient, settings, maxBytes));
    }
}
