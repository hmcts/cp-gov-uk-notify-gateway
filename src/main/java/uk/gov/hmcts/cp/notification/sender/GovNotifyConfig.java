package uk.gov.hmcts.cp.notification.sender;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import uk.gov.service.notify.NotificationClient;

import java.net.InetSocketAddress;
import java.net.Proxy;

@Configuration
class GovNotifyConfig {
    @Bean
    /* default */ NotificationClient notificationClient(
            @Value("${cp.notification.govnotify.base-url:}") final String baseUrl,
            @Value("${cp.notification.govnotify.api-key:}") final String apiKey,
            @Value("${cp.notification.govnotify.proxy.enabled:false}") final boolean proxyEnabled,
            @Value("${cp.notification.govnotify.proxy.host:}") final String proxyHost,
            @Value("${cp.notification.govnotify.proxy.port:0}") final int proxyPort) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "cp.notification.govnotify.api-key (env CP_NG_GOVNOTIFY_API_KEY) must be set — "
                            + "the Gov.Notify client cannot start without it");
        }
        final Proxy proxy = corporateProxy(proxyEnabled, proxyHost, proxyPort);
        return proxy == null
                ? new NotificationClient(apiKey, baseUrl)
                : new NotificationClient(apiKey, baseUrl, proxy);
    }

    /**
     * Corporate egress proxy for the outbound Gov.Notify HTTPS call, ported from the legacy
     * notification context ({@code gov.notify.proxy.*}). Returns {@code null} — a direct connection —
     * unless the proxy is explicitly enabled for the environment, so environments with direct outbound
     * egress are unaffected.
     */
    private static Proxy corporateProxy(final boolean proxyEnabled, final String proxyHost, final int proxyPort) {
        if (proxyEnabled && (!StringUtils.hasText(proxyHost) || proxyPort <= 0)) {
            throw new IllegalStateException(
                    "cp.notification.govnotify.proxy.enabled is true but the proxy host/port are not set — "
                            + "set cp.notification.govnotify.proxy.host (env CP_NG_GOVNOTIFY_PROXY_HOST) and "
                            + "cp.notification.govnotify.proxy.port (env CP_NG_GOVNOTIFY_PROXY_PORT)");
        }
        return proxyEnabled
                ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort))
                : null;
    }
}
