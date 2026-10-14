package uk.gov.hmcts.cp.notification.material;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Raw Material settings as bound from configuration. Bound as plain strings so that a malformed value
 * never surfaces in a binding-failure report; {@link MaterialSettings#from} validates them at startup.
 */
@ConfigurationProperties(prefix = "cp.notification.material")
public record MaterialProperties(
        String systemUserId,
        String baseUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("35s") Duration readTimeout,
        @DefaultValue("5s") Duration sasConnectTimeout,
        @DefaultValue("30s") Duration sasReadTimeout) {

    @Override
    public String toString() {
        return "MaterialProperties[systemUserId=<redacted>, baseUrl=<redacted>, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", sasConnectTimeout=" + sasConnectTimeout
                + ", sasReadTimeout=" + sasReadTimeout + "]";
    }
}
