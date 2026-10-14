package uk.gov.hmcts.cp.notification.material;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(MaterialProperties.class)
public class MaterialConfig {
    public static final String MATERIAL_HTTP_CLIENT = "materialHttpClient";

    @Bean
    public MaterialSettings materialSettings(final MaterialProperties properties) {
        return MaterialSettings.from(properties);
    }

    // One bare client for both the Material query and the SAS download: no authenticator, no default
    // headers, no redirects. CJSCPPUID and the correlation id are added per request by MaterialClient only;
    // the SAS request adds nothing, so no credential of the gateway's travels with the SAS link.
    @Bean(MATERIAL_HTTP_CLIENT)
    public HttpClient materialHttpClient(final MaterialSettings settings) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
