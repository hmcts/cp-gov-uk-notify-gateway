package uk.gov.hmcts.cp.notification.task;

import static uk.gov.hmcts.cp.notification.logging.Dependency.BLOB_STORAGE;
import static uk.gov.hmcts.cp.notification.logging.Dependency.GOV_UK_NOTIFY;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MICROSOFT_365;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;

import uk.gov.hmcts.cp.notification.logging.Dependency;
import uk.gov.hmcts.cp.notification.logging.Operation;

/**
 * Why {@link TransientFailurePolicy#retryOrFail} is retrying a notification task, so its log names the call that
 * failed and which {@link Dependency} and {@link Operation} it was.
 */
public enum RetryCause {
    GOV_NOTIFY_SEND("invoking the Gov.Notify send API", GOV_UK_NOTIFY, SEND),
    OFFICE365_SEND("invoking the Office 365 send API", MICROSOFT_365, SEND),
    ATTACHMENT_DOWNLOAD("downloading the attachment from blob storage", BLOB_STORAGE, DOWNLOAD),
    MATERIAL_DOWNLOAD("downloading the Material document", MATERIAL, DOWNLOAD),
    STATUS_CHECK("invoking the Gov.Notify status API", GOV_UK_NOTIFY, Operation.STATUS_CHECK);

    private final String description;
    private final Dependency dependency;
    private final Operation operation;

    RetryCause(final String description, final Dependency dependency, final Operation operation) {
        this.description = description;
        this.dependency = dependency;
        this.operation = operation;
    }

    public String getDescription() {
        return description;
    }

    public Dependency getDependency() {
        return dependency;
    }

    public Operation getOperation() {
        return operation;
    }
}
