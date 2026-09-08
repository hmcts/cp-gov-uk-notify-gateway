package uk.gov.hmcts.cp.notification.sender;

import org.junit.jupiter.api.Test;

import uk.gov.service.notify.NotificationClient;

import java.net.InetSocketAddress;
import java.net.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class GovNotifyConfigTest {
    private static final String BASE_URL = "https://api.notifications.service.gov.uk";
    private static final String VALID_KEY =
            "cpngtest-00000000-0000-0000-0000-000000000000-11111111-1111-1111-1111-111111111111";
    private static final String PROXY_HOST = "proxy.internal.example";
    private static final int PROXY_PORT = 3128;

    private final GovNotifyConfig config = new GovNotifyConfig();

    @Test
    void fails_to_start_with_an_actionable_message_when_the_api_key_is_blank() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> config.notificationClient(BASE_URL, "  ", false, "", 0))
                .withMessageContaining("cp.notification.govnotify.api-key");
    }

    @Test
    void builds_the_client_when_the_api_key_is_present() {
        assertThatCode(() -> config.notificationClient(BASE_URL, VALID_KEY, false, "", 0))
                .doesNotThrowAnyException();
    }

    @Test
    void builds_a_direct_client_with_no_proxy_when_the_proxy_is_disabled() {
        final NotificationClient client = config.notificationClient(BASE_URL, VALID_KEY, false, PROXY_HOST, PROXY_PORT);

        assertThat(client.getProxy()).isNull();
    }

    @Test
    void routes_through_the_corporate_proxy_when_enabled_with_a_host_and_port() {
        final NotificationClient client = config.notificationClient(BASE_URL, VALID_KEY, true, PROXY_HOST, PROXY_PORT);

        final Proxy proxy = client.getProxy();
        assertThat(proxy).isNotNull();
        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(proxy.address()).isInstanceOf(InetSocketAddress.class);
        final InetSocketAddress address = (InetSocketAddress) proxy.address();
        assertThat(address.getHostString()).isEqualTo(PROXY_HOST);
        assertThat(address.getPort()).isEqualTo(PROXY_PORT);
    }

    @Test
    void fails_to_start_with_an_actionable_message_when_the_proxy_is_enabled_without_a_host() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> config.notificationClient(BASE_URL, VALID_KEY, true, "  ", PROXY_PORT))
                .withMessageContaining("cp.notification.govnotify.proxy.host");
    }

    @Test
    void fails_to_start_with_an_actionable_message_when_the_proxy_is_enabled_without_a_port() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> config.notificationClient(BASE_URL, VALID_KEY, true, PROXY_HOST, 0))
                .withMessageContaining("cp.notification.govnotify.proxy.port");
    }
}
