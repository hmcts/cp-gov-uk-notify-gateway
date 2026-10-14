package uk.gov.hmcts.cp.notification.material;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MaterialProperties.class)
public class MaterialConfig {

    @Bean
    public MaterialSettings materialSettings(final MaterialProperties properties) {
        return MaterialSettings.from(properties);
    }
}
