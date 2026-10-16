package uk.gov.hmcts.cp.notification.material;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import uk.gov.hmcts.cp.notification.integration.config.WireMockSasLinkValidator;
import uk.gov.hmcts.cp.notification.integration.stubs.MaterialServiceStub;
import uk.gov.hmcts.cp.notification.integration.stubs.support.WireMockSupport;
import uk.gov.hmcts.cp.notification.sender.Attachment;

import java.net.http.HttpClient;
import java.time.Duration;
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
                    .hasFieldOrPropertyWithValue("statusCode", 302)
                    .hasFieldOrPropertyWithValue("retryable", true);

            material.redirectWasNotFollowed();
        }
    }

    @Nested
    class WhenTheMaterialLinkIsRefused {

        @Test
        void refuses_a_link_on_an_unrecognised_host_permanently_without_calling_material() {
            assertThatThrownBy(() -> downloader().download(material.unrecognisedLinkFor(materialId), notificationId))
                    .isInstanceOf(MaterialAttachmentException.class)
                    .hasFieldOrPropertyWithValue("statusCode", null)
                    .hasFieldOrPropertyWithValue("retryable", false);

            material.materialWasNotCalled();
        }
    }

    @Nested
    class WhenMaterialRejectsTheRequest {

        @ParameterizedTest
        @ValueSource(ints = {400, 413})
        void fails_permanently_with_the_status_material_returned(final int status) {
            material.materialWillAnswer(materialId, status);

            assertFailure(status, false);
            material.documentWasNotDownloaded();
        }
    }

    @Nested
    class WhenMaterialCannotServeTheDocumentYet {

        @ParameterizedTest
        @ValueSource(ints = {401, 403, 404, 409, 500, 503})
        void fails_retryably_with_the_status_material_returned(final int status) {
            material.materialWillAnswer(materialId, status);

            assertFailure(status, true);
        }

        @Test
        void fails_retryably_when_material_answers_without_a_document_link() {
            material.materialWillAnswerWithoutADocumentLink(materialId, FILENAME);

            assertFailure(200, true);
        }

        @Test
        void fails_retryably_when_material_answers_without_a_filename() {
            material.materialWillAnswerWithoutAFilename(materialId, FILENAME);

            assertFailure(200, true);
        }

        @Test
        void fails_retryably_with_no_status_when_material_drops_the_connection() {
            material.materialWillDropTheConnection(materialId);

            assertFailure(null, true);
        }

        @Test
        void fails_retryably_with_no_status_when_material_does_not_answer_in_time() {
            material.materialWillAnswerAfter(materialId, FILENAME, Duration.ofSeconds(2));

            assertThatThrownBy(() -> downloaderWithTimeouts(Duration.ofMillis(300), Duration.ofSeconds(30))
                    .download(material.materialClientLinkFor(materialId), notificationId))
                    .isInstanceOf(MaterialAttachmentException.class)
                    .hasFieldOrPropertyWithValue("statusCode", null)
                    .hasFieldOrPropertyWithValue("retryable", true);
        }
    }

    @Nested
    class WhenTheDocumentLinkIsRejected {

        @Test
        void fails_permanently_without_downloading_a_link_outside_blob_storage() {
            material.materialWillAnswerWithALinkOutsideBlobStorage(materialId, FILENAME);

            assertFailure(null, false);
            material.documentWasNotDownloaded();
        }

        @Test
        void fails_permanently_when_storage_rejects_the_link_as_invalid() {
            material.documentLinkWillAnswer(materialId, FILENAME, 400);

            assertFailure(400, false);
        }
    }

    @Nested
    class WhenTheDocumentCannotBeDownloadedYet {

        @ParameterizedTest
        @ValueSource(ints = {403, 404, 500, 503})
        void fails_retryably_with_the_status_storage_returned(final int status) {
            material.documentLinkWillAnswer(materialId, FILENAME, status);

            assertFailure(status, true);
        }

        @Test
        void fails_retryably_with_no_status_when_the_download_does_not_finish_in_time() {
            material.documentWillBeServedAfter(materialId, FILENAME, DOCUMENT, Duration.ofSeconds(2));

            assertThatThrownBy(() -> downloaderWithTimeouts(Duration.ofSeconds(35), Duration.ofMillis(300))
                    .download(material.materialClientLinkFor(materialId), notificationId))
                    .isInstanceOf(MaterialAttachmentException.class)
                    .hasFieldOrPropertyWithValue("statusCode", null)
                    .hasFieldOrPropertyWithValue("retryable", true);
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
                    .hasFieldOrPropertyWithValue("retryable", false)
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
                    .hasFieldOrPropertyWithValue("retryable", false)
                    .hasMessage("The Material document passed the maximum attachment size of "
                            + (DOCUMENT.length - 1) + " bytes while downloading");
        }
    }

    private void assertFailure(final Integer status, final boolean retryable) {
        assertThatThrownBy(() -> downloader().download(material.materialClientLinkFor(materialId), notificationId))
                .isInstanceOf(MaterialAttachmentException.class)
                .hasFieldOrPropertyWithValue("statusCode", status)
                .hasFieldOrPropertyWithValue("retryable", retryable);
    }

    private static MaterialAttachmentDownloader downloader() {
        return downloaderWithMaxBytes(MAX_ATTACHMENT_BYTES);
    }

    private static MaterialAttachmentDownloader downloaderWithMaxBytes(final long maxBytes) {
        return downloaderFor(aMaterialSettings(MaterialServiceStub.baseUrl()), maxBytes);
    }

    private static MaterialAttachmentDownloader downloaderWithTimeouts(
            final Duration readTimeout, final Duration sasReadTimeout) {
        return downloaderFor(aMaterialSettings(MaterialServiceStub.baseUrl(), readTimeout, sasReadTimeout),
                MAX_ATTACHMENT_BYTES);
    }

    private static MaterialAttachmentDownloader downloaderFor(final MaterialSettings settings, final long maxBytes) {
        final HttpClient sharedClient = new MaterialConfig().materialHttpClient(settings);
        return new MaterialAttachmentDownloader(
                new MaterialUrlResolver(new MaterialHostMatcher(settings), new LoopbackMaterialLinkRewriter(settings)),
                new MaterialClient(sharedClient, settings),
                new WireMockSasLinkValidator(),
                new SasDocumentFetcher(sharedClient, settings, maxBytes));
    }
}
