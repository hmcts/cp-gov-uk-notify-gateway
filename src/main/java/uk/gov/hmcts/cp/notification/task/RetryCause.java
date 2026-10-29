package uk.gov.hmcts.cp.notification.task;

/**
 * Why {@link TransientFailurePolicy#retryOrFail} is retrying a notification task, so its log names the call that
 * failed. {@link #STILL_IN_PROGRESS} is not a failure: Gov.Notify answered, but with a status that is not final.
 */
public enum RetryCause {
    GOV_NOTIFY_SEND("invoking the Gov.Notify send API"),
    OFFICE365_SEND("invoking the Office 365 send API"),
    ATTACHMENT_DOWNLOAD("downloading the attachment from blob storage"),
    MATERIAL_DOWNLOAD("downloading the Material document"),
    STATUS_CHECK("invoking the Gov.Notify status API"),
    STILL_IN_PROGRESS("polling Gov.Notify for a final status");

    private final String description;

    RetryCause(final String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
