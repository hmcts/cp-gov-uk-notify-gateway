package uk.gov.hmcts.cp.notification.failure;

/** Where the gateway downloads a document from, each with its own answer to which status codes fail at once. */
public enum DownloadSource {
    /** Asking Material for a document link ({@code materialUrl}). */
    MATERIAL_DOCUMENT_LINK,
    /** Downloading the document from the SAS link Material gave. */
    SAS_DOCUMENT,
    /** Downloading an email attachment from blob storage ({@code fileUri}). */
    BLOB_ATTACHMENT
}
