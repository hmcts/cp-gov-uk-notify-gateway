package uk.gov.hmcts.cp.notification.integration.testdata;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;

import java.util.UUID;

public final class SendEmailCommandFactory {
    public static final String MATERIAL_ID = "5b4e1c2a-0d3f-4a6b-9c8d-7e6f5a4b3c2d";
    public static final String MATERIAL_URL = "http://localhost:8080/material-query-api/query/api/rest/material/material/"
            + MATERIAL_ID + "?stream=true&requestPdf=true";

    private SendEmailCommandFactory() {
    }

    public static SendEmailCommand.SendEmailCommandBuilder aSendEmailCommand() {
        return SendEmailCommand.builder()
                .notificationId(UUID.randomUUID())
                .templateId(UUID.randomUUID())
                .sendToAddress("user@example.com")
                .fileUri("https://sa.blob.core.windows.net/mi-reportdata/report.csv");
    }

    public static SendEmailCommand.SendEmailCommandBuilder aSendEmailCommandWithMaterialUrl() {
        return aSendEmailCommand()
                .fileUri(null)
                .materialUrl(MATERIAL_URL);
    }
}
