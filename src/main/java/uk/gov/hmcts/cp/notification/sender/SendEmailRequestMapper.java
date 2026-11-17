package uk.gov.hmcts.cp.notification.sender;

import static java.util.Optional.ofNullable;

import com.azure.storage.blob.BlobUrlParts;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;

import java.util.Optional;

/**
 * Builds the request an email client sends from the command and its downloaded attachment. The attachment is named
 * as its download source reported it (Material's {@code Content-Location}), otherwise after the last segment of the
 * command's blob {@code fileUri}.
 */
@Component
public class SendEmailRequestMapper {

    public SendEmailRequest requestFor(final SendEmailCommand command, final Attachment attachment) {
        final Optional<Attachment> possibleAttachment = ofNullable(attachment);
        final Optional<byte[]> attachmentContent = possibleAttachment.map(Attachment::content);
        final Optional<String> attachmentFilename = possibleAttachment.flatMap(
                downloadedAttachment -> attachmentFilename(command, downloadedAttachment));
        return new SendEmailRequest(
                command.notificationId(),
                command.templateId(),
                command.sendToAddress(),
                command.personalisation(),
                attachmentContent.orElse(null),
                attachmentFilename.orElse(null),
                command.replyToAddress(),
                command.replyToAddressId());
    }

    private Optional<String> attachmentFilename(final SendEmailCommand command, final Attachment attachment) {
        final Optional<String> reportedFilename = ofNullable(attachment.filename());
        return reportedFilename.or(() -> lastSegmentOfBlobName(command.fileUri()));
    }

    private Optional<String> lastSegmentOfBlobName(final String fileUri) {
        final Optional<String> blobName = ofNullable(fileUri)
                .filter(StringUtils::hasText)
                .map(BlobUrlParts::parse)
                .map(BlobUrlParts::getBlobName);
        return blobName
                .map(name -> name.substring(name.lastIndexOf('/') + 1))
                .filter(StringUtils::hasText);
    }
}
