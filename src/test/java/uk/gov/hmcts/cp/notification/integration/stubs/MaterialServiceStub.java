package uk.gov.hmcts.cp.notification.integration.stubs;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.springframework.test.context.DynamicPropertyRegistry;

import uk.gov.hmcts.cp.notification.integration.stubs.support.WireMockSupport;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

public final class MaterialServiceStub {
    private static final String CONTEXT_ROOT = "/material-service";
    private static final String MATERIAL_QUERY_PATH = "/query/api/rest/material/material/";
    private static final String MATERIAL_CLIENT_LIBRARY_BASE = "http://localhost:8080/material-query-api";
    private static final String MATERIAL_QUERY_PARAMETERS = "?stream=true&requestPdf=true";
    private static final String MATERIAL_MEDIA_TYPE = "application/vnd.material.query.material+json";
    private static final String SAS_PATH_PREFIX = "/material-blobs/";
    private static final String SAS_QUERY = "?sv=2020-04-08&se=2026-10-14T10%3A30%3A00Z&sr=b&sp=r&sig=c3ludGhldGlj";
    private static final String UNRECOGNISED_BASE = "http://unrecognised.example.invalid/material-query-api";
    private static final String OUTSIDE_BLOB_STORAGE_BASE = "http://outside-blob-storage.example.invalid";
    private static final String UNAVAILABLE_SCENARIO = "material document briefly unavailable";
    private static final String AVAILABLE = "available";

    private MaterialServiceStub() {
    }

    public static MaterialServiceStub aMaterialService() {
        return new MaterialServiceStub();
    }

    public static void registerProperties(final DynamicPropertyRegistry registry) {
        registry.add("cp.notification.material.base-url", MaterialServiceStub::baseUrl);
    }

    public static String baseUrl() {
        return WireMockSupport.baseUrl() + CONTEXT_ROOT;
    }

    public String materialClientLinkFor(final String materialId) {
        return MATERIAL_CLIENT_LIBRARY_BASE + MATERIAL_QUERY_PATH + materialId + MATERIAL_QUERY_PARAMETERS;
    }

    public String materialServiceLinkFor(final String materialId) {
        return baseUrl() + MATERIAL_QUERY_PATH + materialId + MATERIAL_QUERY_PARAMETERS;
    }

    public String unrecognisedLinkFor(final String materialId) {
        return UNRECOGNISED_BASE + MATERIAL_QUERY_PATH + materialId + MATERIAL_QUERY_PARAMETERS;
    }

    public MaterialServiceStub materialWillAnswer(final String materialId, final int status) {
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(materialQueryPath(materialId)))
                .willReturn(aResponse().withStatus(status)));
        return this;
    }

    public MaterialServiceStub materialWillAnswerWithoutADocumentLink(final String materialId, final String filename) {
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(materialQueryPath(materialId)))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Location", "filename=" + filename)));
        return this;
    }

    public MaterialServiceStub materialWillAnswerWithoutAFilename(final String materialId, final String filename) {
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(materialQueryPath(materialId)))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Location", WireMockSupport.baseUrl() + sasPathFor(materialId, filename) + SAS_QUERY)));
        return this;
    }

    public MaterialServiceStub materialWillAnswerAfter(
            final String materialId, final String filename, final Duration delay) {
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(materialQueryPath(materialId)))
                .willReturn(materialAnswerWithLinkTo(filename, sasPathFor(materialId, filename))
                        .withFixedDelay((int) delay.toMillis())));
        return this;
    }

    public MaterialServiceStub materialWillDropTheConnection(final String materialId) {
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(materialQueryPath(materialId)))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        return this;
    }

    public MaterialServiceStub materialWillAnswerWithALinkOutsideBlobStorage(final String materialId, final String filename) {
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(materialQueryPath(materialId)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Location", OUTSIDE_BLOB_STORAGE_BASE + sasPathFor(materialId, filename) + SAS_QUERY)
                        .withHeader("Content-Location", "filename=" + filename)));
        return this;
    }

    public MaterialServiceStub documentWillBeUnavailableOnceThenServed(
            final String materialId, final String filename, final byte[] content) {
        documentWillBeServed(materialId, filename, content);
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(materialQueryPath(materialId)))
                .inScenario(UNAVAILABLE_SCENARIO + materialId)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(404))
                .willSetStateTo(AVAILABLE));
        return this;
    }

    public MaterialServiceStub documentLinkWillAnswer(final String materialId, final String filename, final int status) {
        final String sasPath = sasPathFor(materialId, filename);
        materialWillAnswerWithLinkTo(materialId, filename, sasPath);
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(sasPath))
                .willReturn(aResponse().withStatus(status)));
        return this;
    }

    public MaterialServiceStub documentWillBeServedAfter(
            final String materialId, final String filename, final byte[] content, final Duration delay) {
        final String sasPath = sasPathFor(materialId, filename);
        materialWillAnswerWithLinkTo(materialId, filename, sasPath);
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(sasPath))
                .willReturn(aResponse().withStatus(200).withBody(content).withFixedDelay((int) delay.toMillis())));
        return this;
    }

    public MaterialServiceStub documentLargerThanTheCapWillBeServed(
            final String materialId, final String filename, final long maxAttachmentBytes) {
        return documentWillBeServed(materialId, filename, new byte[Math.toIntExact(maxAttachmentBytes + 1)]);
    }

    public MaterialServiceStub documentWillBeServed(final String materialId, final String filename, final byte[] content) {
        final String sasPath = sasPathFor(materialId, filename);
        materialWillAnswerWithLinkTo(materialId, filename, sasPath);
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(sasPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/pdf")
                        .withHeader("Content-Length", String.valueOf(content.length))
                        .withBody(content)));
        return this;
    }

    public MaterialServiceStub documentLinkWillRedirect(final String materialId, final String filename) {
        final String sasPath = sasPathFor(materialId, filename);
        materialWillAnswerWithLinkTo(materialId, filename, sasPath);
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(sasPath))
                .willReturn(aResponse().withStatus(302).withHeader("Location", redirectTargetUrl())));
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(redirectTargetPath()))
                .willReturn(aResponse().withStatus(200).withBody("redirected")));
        return this;
    }

    public MaterialServiceStub documentWillBeServedWithoutDeclaringItsLength(
            final String materialId, final String filename, final byte[] content) {
        final String sasPath = sasPathFor(materialId, filename);
        materialWillAnswerWithLinkTo(materialId, filename, sasPath);
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(sasPath))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/pdf").withBody(content)));
        return this;
    }

    public MaterialServiceStub documentWasRequestedFor(
            final String materialId, final UUID notificationId, final UUID systemUserId) {
        final List<LoggedRequest> requests = WireMockSupport.wiremockServer()
                .findAll(getRequestedFor(urlPathEqualTo(materialQueryPath(materialId))));
        assertThat(requests).as("exactly one document query reaches the Material service").hasSize(1);
        final LoggedRequest request = requests.get(0);
        assertThat(request.getUrl()).as("Material query path and parameters")
                .isEqualTo(materialQueryPath(materialId) + MATERIAL_QUERY_PARAMETERS);
        assertThat(request.getHeader("CJSCPPUID")).as("system user").isEqualTo(systemUserId.toString());
        assertThat(request.getHeader("Accept")).as("Material media type").isEqualTo(MATERIAL_MEDIA_TYPE);
        assertThat(request.getHeader("CPPCLIENTCORRELATIONID")).as("correlation id")
                .isEqualTo(notificationId.toString());
        return this;
    }

    public MaterialServiceStub documentWasDownloadedWithoutCredentials(final String materialId, final String filename) {
        final List<LoggedRequest> downloads = WireMockSupport.wiremockServer()
                .findAll(getRequestedFor(urlPathEqualTo(sasPathFor(materialId, filename))));
        assertThat(downloads).as("exactly one download from the document link").hasSize(1);
        final LoggedRequest download = downloads.get(0);
        assertThat(download.getUrl()).as("the document link is used exactly as Material sent it")
                .isEqualTo(sasPathFor(materialId, filename) + SAS_QUERY);
        assertThat(download.containsHeader("Authorization")).as("no Authorization header").isFalse();
        assertThat(download.containsHeader("CJSCPPUID")).as("no system user").isFalse();
        assertThat(download.containsHeader("CPPCLIENTCORRELATIONID")).as("no correlation id").isFalse();
        return this;
    }

    public MaterialServiceStub documentWasRequestedOnEachAttempt(final String materialId, final int minimumAttempts) {
        assertThat(WireMockSupport.wiremockServer()
                .findAll(getRequestedFor(urlPathEqualTo(materialQueryPath(materialId)))))
                .as("every attempt asks the Material service for a fresh document link")
                .hasSizeGreaterThanOrEqualTo(minimumAttempts);
        return this;
    }

    public MaterialServiceStub materialWasNotCalled() {
        assertThat(WireMockSupport.wiremockServer().findAll(getRequestedFor(urlPathMatching(CONTEXT_ROOT + "/.*"))))
                .as("no request reaches the Material service")
                .isEmpty();
        return this;
    }

    public MaterialServiceStub documentWasNotDownloaded() {
        assertThat(WireMockSupport.wiremockServer().findAll(getRequestedFor(urlPathMatching(SAS_PATH_PREFIX + ".*"))))
                .as("nothing is downloaded from a document link")
                .isEmpty();
        return this;
    }

    public MaterialServiceStub redirectWasNotFollowed() {
        assertThat(WireMockSupport.wiremockServer().findAll(getRequestedFor(urlPathEqualTo(redirectTargetPath()))))
                .as("the redirect target must not be requested")
                .isEmpty();
        return this;
    }

    private void materialWillAnswerWithLinkTo(final String materialId, final String filename, final String sasPath) {
        WireMockSupport.wiremockServer().stubFor(get(urlPathEqualTo(materialQueryPath(materialId)))
                .willReturn(materialAnswerWithLinkTo(filename, sasPath)));
    }

    private static ResponseDefinitionBuilder materialAnswerWithLinkTo(final String filename, final String sasPath) {
        final String sasLink = WireMockSupport.baseUrl() + sasPath + SAS_QUERY;
        return aResponse()
                .withStatus(200)
                .withHeader("Location", sasLink)
                .withHeader("Content-Location", "filename=" + filename)
                .withHeader("Content-Type", "text/uri-list")
                .withBody(sasLink);
    }

    private static String materialQueryPath(final String materialId) {
        return CONTEXT_ROOT + MATERIAL_QUERY_PATH + materialId;
    }

    private static String sasPathFor(final String materialId, final String filename) {
        return SAS_PATH_PREFIX + "20261014093000-abcdefghij-" + materialId + "/" + filename;
    }

    private static String redirectTargetPath() {
        return SAS_PATH_PREFIX + "redirected";
    }

    private static String redirectTargetUrl() {
        return WireMockSupport.baseUrl() + redirectTargetPath();
    }
}
