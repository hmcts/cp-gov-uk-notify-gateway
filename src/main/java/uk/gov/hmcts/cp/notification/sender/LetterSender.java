package uk.gov.hmcts.cp.notification.sender;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.hmcts.cp.notification.logging.LogField.CHANNEL;
import static uk.gov.hmcts.cp.notification.logging.LogField.DEPENDENCY;
import static uk.gov.hmcts.cp.notification.logging.LogField.OPERATION;
import static uk.gov.hmcts.cp.notification.logging.LogField.OUTCOME;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.logging.CallOutcome;
import uk.gov.hmcts.cp.notification.logging.Channel;
import uk.gov.hmcts.cp.notification.logging.Dependency;
import uk.gov.hmcts.cp.notification.logging.Operation;

import java.util.UUID;

/**
 * Sends a letter's PDF to Gov.Notify as a precompiled letter, as {@link EmailSender} sends an email, and logs the
 * send with the call's JSON fields (PEG-3720). A failure is left to the caller, as a {@link GovNotifyException}.
 */
@Component
public class LetterSender {
    private final Logger logger = getLogger(LetterSender.class);

    private final GovNotifyClient govNotifyClient;

    public LetterSender(final GovNotifyClient govNotifyClient) {
        this.govNotifyClient = govNotifyClient;
    }

    /** Returns Gov.Notify's reference for the letter, to poll its status with. */
    public String sendLetter(final UUID notificationId, final byte[] pdf, final boolean firstClass) {
        final String sendingMessage = format(
                "Sending letter for notification '%s' (first class: '%s')", notificationId, firstClass);
        logger.info(sendingMessage);
        final String reference = govNotifyClient.sendPrecompiledLetter(notificationId, pdf, firstClass);
        final String sentMessage = format(
                "Sent letter for notification '%s' via Gov.Notify (reference '%s')", notificationId, reference);
        logger.atInfo()
                .addKeyValue(DEPENDENCY, Dependency.GOV_UK_NOTIFY.getFieldValue())
                .addKeyValue(OPERATION, Operation.SEND.getFieldValue())
                .addKeyValue(CHANNEL, Channel.LETTER.getFieldValue())
                .addKeyValue(OUTCOME, CallOutcome.SUCCEEDED.getFieldValue())
                .log(sentMessage);
        return reference;
    }
}
