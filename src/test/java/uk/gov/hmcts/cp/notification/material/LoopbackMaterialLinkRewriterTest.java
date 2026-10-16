package uk.gov.hmcts.cp.notification.material;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialSettingsFactory.MATERIAL_BASE_URL;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialSettingsFactory.aMaterialSettings;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.MATERIAL_ID;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.MATERIAL_URL;

class LoopbackMaterialLinkRewriterTest {
    private static final String MATERIAL_PATH = "/query/api/rest/material/material/";

    private final LoopbackMaterialLinkRewriter rewriter = new LoopbackMaterialLinkRewriter(aMaterialSettings());

    @Nested
    class WhenDecidingWhetherALinkCanBeRewritten {

        @ParameterizedTest
        @ValueSource(strings = {
                MATERIAL_URL,
                "http://127.0.0.1:8080/material-query-api" + MATERIAL_PATH + "x",
                "http://LOCALHOST:8080/material-query-api" + MATERIAL_PATH + "x"})
        void should_accept_a_loopback_link_under_the_material_query_api(final String link) {
            assertThat(rewriter.canRewrite(URI.create(link))).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://evil.example.com/material-query-api" + MATERIAL_PATH + "x",
                "http://localhost@evil.example.com/material-query-api/query",
                "http://material-service.example.internal/material-query-api" + MATERIAL_PATH + "x"})
        void should_reject_a_link_that_is_not_on_a_loopback_host(final String link) {
            assertThat(rewriter.canRewrite(URI.create(link))).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://localhost:8080/other-service/query/api/rest/material/material/x",
                "http://localhost:8080/material-query-apiX/query"})
        void should_reject_a_loopback_link_outside_the_material_query_api(final String link) {
            assertThat(rewriter.canRewrite(URI.create(link))).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://localhost:8080/material-query-api/../admin",
                "http://localhost:8080/material-query-api/query/api/../../../admin?x=1",
                "http://localhost:8080/material-query-api/query/%2e%2e/%2e%2e/admin",
                "http://localhost:8080/material-query-api/..;/admin",
                "http://localhost:8080/material-query-api/query/..;x=1/..;/admin"})
        void should_reject_a_loopback_link_that_steps_out_of_the_material_query_api(final String link) {
            assertThat(rewriter.canRewrite(URI.create(link))).isFalse();
        }
    }

    @Nested
    class WhenRewritingALink {

        @Test
        void should_replace_the_loopback_origin_and_prefix_with_the_base_url_keeping_the_path_and_query() {
            assertThat(rewriter.rewrite(URI.create(MATERIAL_URL))).isEqualTo(URI.create(
                    MATERIAL_BASE_URL + MATERIAL_PATH + MATERIAL_ID + "?stream=true&requestPdf=true"));
        }

        @Test
        void should_rewrite_a_link_without_a_query() {
            assertThat(rewriter.rewrite(URI.create("http://127.0.0.1:8080/material-query-api" + MATERIAL_PATH + MATERIAL_ID)))
                    .isEqualTo(URI.create(MATERIAL_BASE_URL + MATERIAL_PATH + MATERIAL_ID));
        }

        @Test
        void should_keep_encoded_characters_in_the_path_as_sent() {
            assertThat(rewriter.rewrite(URI.create("http://localhost:8080/material-query-api" + MATERIAL_PATH + "a%20b%2Fc")))
                    .isEqualTo(URI.create(MATERIAL_BASE_URL + MATERIAL_PATH + "a%20b%2Fc"));
        }

        @Test
        void should_join_cleanly_when_the_base_url_ends_with_a_slash() {
            final LoopbackMaterialLinkRewriter trailingSlash =
                    new LoopbackMaterialLinkRewriter(aMaterialSettings(MATERIAL_BASE_URL + "/"));

            assertThat(trailingSlash.rewrite(URI.create(MATERIAL_URL)).toString())
                    .startsWith(MATERIAL_BASE_URL + MATERIAL_PATH);
        }
    }
}
