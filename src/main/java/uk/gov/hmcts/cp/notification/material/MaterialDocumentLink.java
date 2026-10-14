package uk.gov.hmcts.cp.notification.material;

/**
 * Material's answer to a document query: the short-lived SAS link (a bearer credential — memory only,
 * never persisted or logged) and the document's filename.
 */
public record MaterialDocumentLink(String sasLink, String filename) {

    @Override
    public String toString() {
        return "MaterialDocumentLink[sasLink=<redacted>, filename=<redacted>]";
    }
}
