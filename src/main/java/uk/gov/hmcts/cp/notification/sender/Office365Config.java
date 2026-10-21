package uk.gov.hmcts.cp.notification.sender;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(Office365Properties.class)
public class Office365Config {
    public static final String OFFICE365_HTTP_CLIENT = "office365HttpClient";

    @Bean
    public Office365Settings office365Settings(final Office365Properties properties) {
        return Office365Settings.from(properties);
    }

    // Unlike legacy (no timeouts), connect and request are bounded (ADR-002 D13): the call runs inside the task's
    // transaction, so a hung connection would hold a DB connection and the job lock. A timeout is retried.
    @Bean(OFFICE365_HTTP_CLIENT)
    public HttpClient office365HttpClient(final Office365Settings settings) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
