package uk.gov.hmcts.cp.notification.sender;

import com.azure.storage.blob.BlobUrlParts;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;

@Component
@RequiredArgsConstructor
public class EmailSender {
    private static final Logger LOG = LoggerFactory.getLogger(EmailSender.class);

    private final EmailClientFactory emailClientFactory;

    public SendResult sendEmail(final SendEmailCommand command, final Attachment attachment) {
        final SendEmailRequest request = new SendEmailRequest(
                command.notificationId(),
                command.templateId(),
                command.sendToAddress(),
                command.personalisation(),
                attachment == null ? null : attachment.content(),
                attachmentFilename(command, attachment),
                command.replyToAddress(),
                command.replyToAddressId());

        final EmailClient client = emailClientFactory.selectFor(request);
        final SendResult result = client.send(request);
        LOG.info("Sent notification {} via {} (reference {})",
                command.notificationId(), client.getClass().getSimpleName(), result.reference());
        return result;
    }

    private static String attachmentFilename(final SendEmailCommand command, final Attachment attachment) {
        String filename = null;
        if (attachment != null) {
            filename = attachment.filename() == null ? filenameFrom(command.fileUri()) : attachment.filename();
        }
        return filename;
    }

    private static String filenameFrom(final String fileUri) {
        String filename = null;
        if (fileUri != null && !fileUri.isBlank()) {
            final String blobName = BlobUrlParts.parse(fileUri).getBlobName();
            if (blobName != null && !blobName.isBlank()) {
                final String segment = blobName.substring(blobName.lastIndexOf('/') + 1);
                if (!segment.isBlank()) {
                    filename = segment;
                }
            }
        }
        return filename;
    }
}
