package uk.gov.hmcts.cp.notification.integration.testdata;

import uk.gov.hmcts.cp.notification.material.MaterialDocumentLink;

import static uk.gov.hmcts.cp.notification.integration.testdata.AttachmentFactory.MATERIAL_DOCUMENT_FILENAME;

public final class MaterialDocumentLinkFactory {
    public static final String SAS_HOST = "materialdocs.blob.core.windows.net";
    public static final String SAS_QUERY = "sv=2020-04-08&se=2026-10-14T10%3A30Z&sig=c2VjcmV0";
    public static final String SAS_LINK = "https://" + SAS_HOST + "/material/" + MATERIAL_DOCUMENT_FILENAME + "?" + SAS_QUERY;

    private MaterialDocumentLinkFactory() {
    }

    public static MaterialDocumentLink aMaterialDocumentLink() {
        return new MaterialDocumentLink(SAS_LINK, MATERIAL_DOCUMENT_FILENAME);
    }
}
