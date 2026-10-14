package uk.gov.hmcts.cp.notification.integration.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import uk.gov.hmcts.cp.notification.material.SasLinkValidator;

@TestConfiguration
public class MaterialSasTestConfig {

    @Bean
    @Primary
    public SasLinkValidator wireMockSasLinkValidator() {
        return new WireMockSasLinkValidator();
    }
}
