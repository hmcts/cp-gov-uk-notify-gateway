package uk.gov.hmcts.cp.notification.acceptance.steps;

import io.cucumber.spring.ScenarioScope;
import org.springframework.stereotype.Component;

@Component
@ScenarioScope
public class ScenarioContext {

    private String notificationId;
    private String templateId;
    private String externalReference;
    private String commandJson;
    private String replyQueue;
    private byte[] attachmentBytes;

    public String getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(final String notificationId) {
        this.notificationId = notificationId;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(final String templateId) {
        this.templateId = templateId;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public void setExternalReference(final String externalReference) {
        this.externalReference = externalReference;
    }

    public String getCommandJson() {
        return commandJson;
    }

    public void setCommandJson(final String commandJson) {
        this.commandJson = commandJson;
    }

    public String getReplyQueue() {
        return replyQueue;
    }

    public void setReplyQueue(final String replyQueue) {
        this.replyQueue = replyQueue;
    }

    public byte[] getAttachmentBytes() {
        return attachmentBytes;
    }

    public void setAttachmentBytes(final byte[] attachmentBytes) {
        this.attachmentBytes = attachmentBytes;
    }
}
