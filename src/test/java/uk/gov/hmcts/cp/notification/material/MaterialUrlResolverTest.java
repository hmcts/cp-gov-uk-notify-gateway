package uk.gov.hmcts.cp.notification.material;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialSettingsFactory.MATERIAL_BASE_URL;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialSettingsFactory.aMaterialSettings;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.MATERIAL_ID;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.MATERIAL_URL;

class MaterialUrlResolverTest {
    private static final String REFUSED = "Material link is not on the Material service";
    private static final String MATERIAL_PATH = "/query/api/rest/material/material/";

    private final MaterialUrlResolver resolver = new MaterialUrlResolver(aMaterialSettings());

    private void assertRefused(final String link) {
        assertThatThrownBy(() -> resolver.resolve(link))
                .isInstanceOf(MaterialAttachmentException.class)
                .hasMessage(REFUSED)
                .hasFieldOrPropertyWithValue("statusCode", null)
                .hasFieldOrPropertyWithValue("retryable", false)
                .hasNoCause();
    }

    @Nested
    class WhenTheLinkIsTheOneMaterialsClientLibraryBuilds {

        @Test
        void should_rewrite_it_onto_the_material_base_url_keeping_the_path_and_query() {
            assertThat(resolver.resolve(MATERIAL_URL)).isEqualTo(URI.create(
                    MATERIAL_BASE_URL + MATERIAL_PATH + MATERIAL_ID + "?stream=true&requestPdf=true"));
        }

        @Test
        void should_rewrite_a_loopback_address_link_without_a_query_the_same_way() {
            assertThat(resolver.resolve("http://127.0.0.1:8080/material-query-api" + MATERIAL_PATH + MATERIAL_ID))
                    .isEqualTo(URI.create(MATERIAL_BASE_URL + MATERIAL_PATH + MATERIAL_ID));
        }

        @Test
        void should_recognise_localhost_whatever_its_case() {
            assertThat(resolver.resolve("http://LOCALHOST:8080/material-query-api" + MATERIAL_PATH + MATERIAL_ID))
                    .isEqualTo(URI.create(MATERIAL_BASE_URL + MATERIAL_PATH + MATERIAL_ID));
        }

        @Test
        void should_ignore_whitespace_around_the_link() {
            assertThat(resolver.resolve("  " + MATERIAL_URL + " ")).isEqualTo(resolver.resolve(MATERIAL_URL));
        }

        @Test
        void should_keep_encoded_characters_in_the_path_as_sent() {
            assertThat(resolver.resolve("http://localhost:8080/material-query-api" + MATERIAL_PATH + "a%20b%2Fc"))
                    .isEqualTo(URI.create(MATERIAL_BASE_URL + MATERIAL_PATH + "a%20b%2Fc"));
        }

        @Test
        void should_join_cleanly_when_the_base_url_ends_with_a_slash() {
            final MaterialUrlResolver trailingSlash = new MaterialUrlResolver(aMaterialSettings(MATERIAL_BASE_URL + "/"));

            assertThat(trailingSlash.resolve(MATERIAL_URL).toString())
                    .startsWith(MATERIAL_BASE_URL + MATERIAL_PATH);
        }
    }

    @Nested
    class WhenTheLinkIsAlreadyOnTheMaterialService {

        @ParameterizedTest
        @ValueSource(strings = {
                "http://material-service.example.internal/material-service/query/api/rest/material/material/x?stream=true",
                "http://material-service.example.internal:80/material-service/query/api/rest/material/material/x",
                "HTTP://Material-Service.Example.Internal/material-service/query/api/rest/material/material/x"})
        void should_use_it_exactly_as_sent(final String link) {
            assertThat(resolver.resolve(link)).isEqualTo(URI.create(link));
        }

        @Test
        void should_use_a_link_on_a_localhost_base_url_as_sent_rather_than_rewriting_it() {
            final MaterialUrlResolver localBase = new MaterialUrlResolver(
                    aMaterialSettings("http://localhost:9561/material-service"));
            final String onBase = "http://localhost:9561/material-service" + MATERIAL_PATH + "x";

            assertThat(localBase.resolve(onBase)).isEqualTo(URI.create(onBase));
        }

        @Test
        void should_still_rewrite_a_client_library_link_when_the_base_url_is_itself_on_localhost() {
            final MaterialUrlResolver localBase = new MaterialUrlResolver(
                    aMaterialSettings("http://localhost:9561/material-service"));

            assertThat(localBase.resolve(MATERIAL_URL).toString())
                    .startsWith("http://localhost:9561/material-service" + MATERIAL_PATH);
        }
    }

    @Nested
    class WhenTheLinkIsNotOnTheMaterialService {

        @Test
        void should_refuse_a_link_on_another_host() {
            assertRefused("http://evil.example.com/material-query-api" + MATERIAL_PATH + "x");
        }

        @Test
        void should_refuse_a_link_on_the_material_host_with_a_different_scheme() {
            assertRefused("https://material-service.example.internal/material-service" + MATERIAL_PATH + "x");
        }

        @Test
        void should_refuse_a_link_on_the_material_host_with_a_different_port() {
            assertRefused("http://material-service.example.internal:8080/material-service" + MATERIAL_PATH + "x");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://localhost:8080/other-service/query/api/rest/material/material/x",
                "http://localhost:8080/material-query-apiX/query"})
        void should_refuse_a_localhost_link_outside_the_material_query_api(final String link) {
            assertRefused(link);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://localhost:8080/material-query-api/../admin",
                "http://localhost:8080/material-query-api/query/api/../../../admin?x=1",
                "http://localhost:8080/material-query-api/query/%2e%2e/%2e%2e/admin",
                "http://localhost:8080/material-query-api/..;/admin",
                "http://localhost:8080/material-query-api/query/..;x=1/..;/admin"})
        void should_refuse_a_localhost_link_that_steps_out_of_the_material_query_api(final String link) {
            assertRefused(link);
        }

        @Test
        void should_refuse_a_link_that_hides_another_host_behind_localhost_user_info() {
            assertRefused("http://localhost@evil.example.com/material-query-api/query");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/material-query-api/query/api/rest/material/material/x",
                "http:///material-query-api/query/api/rest/material/material/x",
                "mailto:someone@example.com"})
        void should_refuse_a_link_without_a_scheme_or_host(final String link) {
            assertRefused(link);
        }

        @Test
        void should_refuse_text_that_is_not_a_url() {
            assertRefused("not a url at all");
        }
    }
}
