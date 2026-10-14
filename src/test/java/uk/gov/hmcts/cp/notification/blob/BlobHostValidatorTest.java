package uk.gov.hmcts.cp.notification.blob;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class BlobHostValidatorTest {
    private static final String ALLOWED_HOST = "examplefilestore.blob.core.windows.net";

    @Test
    void allows_an_https_uri_whose_host_is_in_the_allow_list() {
        final BlobHostValidator validator = new BlobHostValidator(List.of(ALLOWED_HOST));

        assertThatCode(() -> validator.validate("https", ALLOWED_HOST)).doesNotThrowAnyException();
    }

    @Test
    void matches_the_host_case_insensitively() {
        final BlobHostValidator validator = new BlobHostValidator(List.of(ALLOWED_HOST));

        assertThatCode(() -> validator.validate("https", "ExampleFileStore.Blob.Core.Windows.Net"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_a_host_that_is_not_in_the_allow_list() {
        final BlobHostValidator validator = new BlobHostValidator(List.of(ALLOWED_HOST));

        assertThatExceptionOfType(DisallowedBlobHostException.class)
                .isThrownBy(() -> validator.validate("https", "attacker.example.com"));
    }

    @Test
    void rejects_a_non_https_scheme_even_for_an_allow_listed_host() {
        final BlobHostValidator validator = new BlobHostValidator(List.of(ALLOWED_HOST));

        assertThatExceptionOfType(DisallowedBlobHostException.class)
                .isThrownBy(() -> validator.validate("http", ALLOWED_HOST));
    }

    @Test
    void rejects_every_host_when_the_allow_list_is_empty() {
        final BlobHostValidator validator = new BlobHostValidator(List.of());

        assertThatExceptionOfType(DisallowedBlobHostException.class)
                .isThrownBy(() -> validator.validate("https", ALLOWED_HOST));
    }

    @Test
    void ignores_blank_entries_when_building_the_allow_list() {
        final BlobHostValidator validator = new BlobHostValidator(List.of("  ", ALLOWED_HOST, ""));

        assertThatCode(() -> validator.validate("https", ALLOWED_HOST)).doesNotThrowAnyException();
    }
}
