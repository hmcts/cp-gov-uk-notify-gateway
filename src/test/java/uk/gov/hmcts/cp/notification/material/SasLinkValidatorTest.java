package uk.gov.hmcts.cp.notification.material;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SasLinkValidatorTest {
    private static final String SAS_LINK =
            "https://materialdocs.blob.core.windows.net/material/d20.pdf?sv=2020-04-08&se=2026-10-13T10%3A30Z&sig=abc%3D";

    private final SasLinkValidator validator = new SasLinkValidator();

    @Test
    void should_accept_an_https_link_on_an_azure_blob_storage_host() {
        assertThat(validator.validate(SAS_LINK)).isEqualTo(URI.create(SAS_LINK));
    }

    @Test
    void should_accept_the_https_scheme_in_any_case() {
        final String upperCaseScheme = SAS_LINK.replaceFirst("https", "HTTPS");

        assertThat(validator.validate(upperCaseScheme)).isEqualTo(URI.create(upperCaseScheme));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://materialdocs.blob.core.windows.net/material/d20.pdf?sig=abc",
            "https://evil.example.com/material/d20.pdf?sig=abc",
            "https://materialdocs.blob.core.windows.net.evil.example.com/d20.pdf?sig=abc",
            "https://blob.core.windows.net/d20.pdf?sig=abc",
            "https://.blob.core.windows.net/d20.pdf?sig=abc",
            "ftp://materialdocs.blob.core.windows.net/d20.pdf",
            "/material/d20.pdf?sig=abc",
            "https://bad host.blob.core.windows.net/d20.pdf"})
    void should_reject_a_link_that_is_not_https_azure_blob_storage_without_revealing_it(final String link) {
        assertThatThrownBy(() -> validator.validate(link))
                .isInstanceOf(MaterialAttachmentException.class)
                .hasMessage("Material returned a document link that is not an https Azure Blob Storage link")
                .hasFieldOrPropertyWithValue("statusCode", null)
                .hasFieldOrPropertyWithValue("retryable", false)
                .hasNoCause();
    }
}
