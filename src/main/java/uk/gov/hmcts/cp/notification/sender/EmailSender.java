package uk.gov.hmcts.cp.notification.sender;

import static java.util.Optional.empty;
import static uk.gov.hmcts.cp.notification.logging.CallOutcome.SUCCEEDED;
import static uk.gov.hmcts.cp.notification.logging.Channel.EMAIL;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;

import com.azure.storage.blob.BlobUrlParts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.logging.Dependency;
import uk.gov.hmcts.cp.notification.logging.DependencyCall;
import uk.gov.hmcts.cp.notification.logging.DependencyCallLogger;

@Component
@RequiredArgsConstructor
public class EmailSender {
    private final EmailClientFactory emailClientFactory;
    private final DependencyCallLogger dependencyCallLogger;

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

        final EmailClient emailClient = emailClientFactory.selectFor(request);
        final SendResult sendResult = emailClient.send(request);
        final Dependency dependency = emailClient.dependency();
        final DependencyCall dependencyCall = new DependencyCall(dependency, SEND, EMAIL, SUCCEEDED, empty());
        final Class<? extends EmailClient> emailClientClass = emailClient.getClass();
        final String emailClientName = emailClientClass.getSimpleName();
        dependencyCallLogger.info(dependencyCall, "Sent notification {} via {} (reference {})",
                command.notificationId(), emailClientName, sendResult.reference());
        return sendResult;
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
