package uk.gov.hmcts.cp.notification.acceptance.steps;

import io.cucumber.spring.ScenarioScope;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport;

@Component
@ScenarioScope
public class ScenarioContext {

    private String notificationId;
    private String templateId;
    private String externalReference;
    private String commandJson;
    private String replyQueue;
    private byte[] attachmentBytes;
    private String attachmentFilename;
    private String materialId;
    private String materialUrl;
    private String commandQueue = ServiceBusContainerSupport.COMMAND_QUEUE;
    private boolean firstClass;

    public String getCommandQueue() {
        return commandQueue;
    }

    public void setCommandQueue(final String commandQueue) {
        this.commandQueue = commandQueue;
    }

    public boolean isFirstClass() {
        return firstClass;
    }

    public void setFirstClass(final boolean firstClass) {
        this.firstClass = firstClass;
    }

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

    public String getAttachmentFilename() {
        return attachmentFilename;
    }

    public void setAttachmentFilename(final String attachmentFilename) {
        this.attachmentFilename = attachmentFilename;
    }

    public String getMaterialId() {
        return materialId;
    }

    public void setMaterialId(final String materialId) {
        this.materialId = materialId;
    }

    public String getMaterialUrl() {
        return materialUrl;
    }

    public void setMaterialUrl(final String materialUrl) {
        this.materialUrl = materialUrl;
    }
}
