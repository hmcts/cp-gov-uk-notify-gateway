package uk.gov.hmcts.cp.notification.sender;

/**
 * A downloaded attachment. {@code filename} is the name the download source reported (Material's
 * {@code Content-Location}); when it is {@code null} the sender derives one from the command's
 * {@code fileUri}.
 */
public record Attachment(byte[] content, String filename) {

    public static Attachment withoutFilename(final byte[] content) {
        return new Attachment(content, null);
    }

    @Override
    public String toString() {
        return "Attachment[bytes=" + (content == null ? 0 : content.length) + ", filename=<redacted>]";
    }
}
