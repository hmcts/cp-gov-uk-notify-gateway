package uk.gov.hmcts.cp.notification.integration.testdata;

import uk.gov.hmcts.cp.notification.sender.Attachment;

import java.nio.charset.StandardCharsets;

public final class AttachmentFactory {
    public static final String MATERIAL_DOCUMENT_FILENAME = "d20.pdf";

    private AttachmentFactory() {
    }

    public static Attachment aMaterialDocumentAttachment() {
        return new Attachment("%PDF-1.4 d20".getBytes(StandardCharsets.UTF_8), MATERIAL_DOCUMENT_FILENAME);
    }

    public static Attachment aFileAttachment() {
        return aFileAttachment("report".getBytes(StandardCharsets.UTF_8));
    }

    public static Attachment aFileAttachment(final byte[] content) {
        return Attachment.withoutFilename(content);
    }
}
