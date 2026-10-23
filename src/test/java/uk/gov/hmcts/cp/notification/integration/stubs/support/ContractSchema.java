package uk.gov.hmcts.cp.notification.integration.stubs.support;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Set;

import static java.nio.file.Files.readString;
import static org.assertj.core.api.Assertions.assertThat;

public final class ContractSchema {
    public static final String NOTIFICATION_SENT = "notification-sent.schema.json";
    public static final String NOTIFICATION_FAILED = "notification-failed.schema.json";
    public static final String SEND_EMAIL_COMMAND = "command-send-email-notification.schema.json";
    public static final String SEND_LETTER_COMMAND = "command-send-letter-notification.schema.json";

    private static final Path CONTRACTS_DIR = Path.of("contracts");
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private ContractSchema() {
    }

    public static void assertConformsTo(final String schemaFile, final String payloadJson) {
        assertThat(violations(schemaFile, payloadJson))
                .as("payload must satisfy the contract schema %s", schemaFile)
                .isEmpty();
    }

    public static Set<ValidationMessage> violations(final String schemaFile, final String payloadJson) {
        return load(schemaFile).validate(payloadJson, InputFormat.JSON);
    }

    private static JsonSchema load(final String schemaFile) {
        try {
            return FACTORY.getSchema(readString(CONTRACTS_DIR.resolve(schemaFile)));
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to read contract schema: " + schemaFile, e);
        }
    }
}
