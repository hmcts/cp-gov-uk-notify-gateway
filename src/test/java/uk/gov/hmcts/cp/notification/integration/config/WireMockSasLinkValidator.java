package uk.gov.hmcts.cp.notification.integration.config;

import uk.gov.hmcts.cp.notification.material.SasLinkValidator;

public class WireMockSasLinkValidator extends SasLinkValidator {
    private static final String WIREMOCK_SCHEME = "http";
    private static final String WIREMOCK_HOST = "localhost";

    @Override
    protected boolean isAllowedScheme(final String scheme) {
        return WIREMOCK_SCHEME.equals(scheme);
    }

    @Override
    protected boolean isAllowedHost(final String host) {
        return WIREMOCK_HOST.equals(host);
    }
}
