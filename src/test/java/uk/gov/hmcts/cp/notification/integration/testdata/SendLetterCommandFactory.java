package uk.gov.hmcts.cp.notification.integration.testdata;

import uk.gov.hmcts.cp.notification.command.SendLetterCommand;

import java.util.UUID;

public final class SendLetterCommandFactory {
    public static final String MATERIAL_ID = "8c1f2e3d-4b5a-4c6d-8e7f-9a0b1c2d3e4f";
    // The link correspondence, progression and resulting build with MaterialUrlGenerator.pdfFileStreamUrlFor.
    public static final String LETTER_URL = "http://localhost:8080/material-query-api/query/api/rest/material/material/"
            + MATERIAL_ID + "?stream=true&requestPdf=true";

    private SendLetterCommandFactory() {
    }

    public static SendLetterCommand.SendLetterCommandBuilder aSendLetterCommand() {
        return SendLetterCommand.builder()
                .notificationId(UUID.randomUUID())
                .letterUrl(LETTER_URL)
                .clientContext("correspondence");
    }

    public static SendLetterCommand.SendLetterCommandBuilder aFirstClassSendLetterCommand() {
        return aSendLetterCommand().postage("first");
    }
}
