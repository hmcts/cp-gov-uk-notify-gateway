package uk.gov.hmcts.cp.notification.blob;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class BlobClientFactoryTest {
    private static final String ALLOWED_HOST = "examplefilestore.blob.core.windows.net";
    private static final String AZURITE_CONNECTION_STRING =
            "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=dGVzdA==;"
                    + "BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;";

    private final BlobHostValidator hostValidator = new BlobHostValidator(List.of(ALLOWED_HOST));

    @Test
    void rejects_a_disallowed_host_before_a_token_is_ever_attached_in_workload_identity_mode() {
        final BlobClientFactory factory = new BlobClientFactory("", hostValidator);

        assertThatExceptionOfType(DisallowedBlobHostException.class)
                .isThrownBy(() -> factory.blobClientFor("https://attacker.example.com/container/blob"));
    }

    @Test
    void builds_a_client_for_an_allow_listed_host_in_workload_identity_mode() {
        final BlobClientFactory factory = new BlobClientFactory("", hostValidator);

        assertThatCode(() -> factory.blobClientFor("https://" + ALLOWED_HOST + "/container/blob"))
                .doesNotThrowAnyException();
    }

    @Test
    void does_not_enforce_the_allow_list_in_connection_string_mode() {
        final BlobClientFactory factory = new BlobClientFactory(AZURITE_CONNECTION_STRING, hostValidator);

        assertThatCode(() -> factory.blobClientFor("https://any-other-account.blob.core.windows.net/c/b"))
                .doesNotThrowAnyException();
    }
}
