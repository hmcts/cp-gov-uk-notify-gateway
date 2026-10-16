package uk.gov.hmcts.cp.notification.material;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialSettingsFactory.aMaterialSettings;

class MaterialHostMatcherTest {
    private static final String MATERIAL_PATH = "/material-service/query/api/rest/material/material/x";

    private final MaterialHostMatcher matcher = new MaterialHostMatcher(aMaterialSettings());

    @ParameterizedTest
    @ValueSource(strings = {
            "http://material-service.example.internal" + MATERIAL_PATH + "?stream=true",
            "http://material-service.example.internal:80" + MATERIAL_PATH,
            "HTTP://Material-Service.Example.Internal" + MATERIAL_PATH})
    void should_match_a_link_with_the_base_urls_scheme_host_and_port(final String link) {
        assertThat(matcher.isOnMaterialHost(URI.create(link))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://evil.example.com" + MATERIAL_PATH,
            "https://material-service.example.internal" + MATERIAL_PATH,
            "http://material-service.example.internal:8080" + MATERIAL_PATH,
            "http://localhost@evil.example.com" + MATERIAL_PATH})
    void should_not_match_a_link_with_a_different_scheme_host_or_port(final String link) {
        assertThat(matcher.isOnMaterialHost(URI.create(link))).isFalse();
    }

    @Test
    void should_match_on_a_localhost_base_url_only_when_the_port_is_the_same() {
        final MaterialHostMatcher localBase = new MaterialHostMatcher(
                aMaterialSettings("http://localhost:9561/material-service"));

        assertThat(localBase.isOnMaterialHost(URI.create("http://localhost:9561" + MATERIAL_PATH))).isTrue();
        assertThat(localBase.isOnMaterialHost(URI.create("http://localhost:8080" + MATERIAL_PATH))).isFalse();
    }
}
