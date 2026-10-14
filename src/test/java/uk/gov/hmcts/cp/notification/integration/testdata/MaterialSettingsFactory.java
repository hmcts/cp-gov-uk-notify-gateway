package uk.gov.hmcts.cp.notification.integration.testdata;

import uk.gov.hmcts.cp.notification.material.MaterialSettings;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

public final class MaterialSettingsFactory {
    public static final UUID SYSTEM_USER_ID = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
    public static final String MATERIAL_BASE_URL = "http://material-service.example.internal/material-service";

    private MaterialSettingsFactory() {
    }

    public static MaterialSettings aMaterialSettings() {
        return aMaterialSettings(MATERIAL_BASE_URL);
    }

    public static MaterialSettings aMaterialSettings(final String baseUrl) {
        return new MaterialSettings(SYSTEM_USER_ID, URI.create(baseUrl),
                Duration.ofSeconds(5), Duration.ofSeconds(35), Duration.ofSeconds(30));
    }
}
