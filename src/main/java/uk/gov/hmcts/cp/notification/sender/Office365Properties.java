package uk.gov.hmcts.cp.notification.sender;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Raw Office 365 route settings as bound from configuration. Both values are credentials: the endpoint
 * carries the Azure Function key in its query string. {@link Office365Settings#from} validates them at startup.
 */
@ConfigurationProperties(prefix = "cp.notification.office365")
public record Office365Properties(
        String endpoint,
        String subscriptionKey,
        @DefaultValue("30s") Duration connectTimeout,
        @DefaultValue("300s") Duration requestTimeout) {

    @Override
    public String toString() {
        return "Office365Properties[endpoint=<redacted>, subscriptionKey=<redacted>, connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout + "]";
    }
}
