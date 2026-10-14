package uk.gov.hmcts.cp.notification.integration.stubs.support;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

public final class WireMockSupport {
    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());

    static {
        SERVER.start();
    }

    private WireMockSupport() {
    }

    public static WireMockServer wiremockServer() {
        return SERVER;
    }

    public static String baseUrl() {
        return "http://localhost:" + SERVER.port();
    }

    public static void reset() {
        SERVER.resetAll();
    }
}
