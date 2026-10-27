package uk.gov.hmcts.cp.notification.task;

import jakarta.json.JsonObject;

import uk.gov.hmcts.cp.notification.command.SendLetterCommand;

import java.util.UUID;

/**
 * What every letter task needs to know about the letter, carried in the job data from task to task. It takes
 * the place of legacy's {@code Notification} aggregate state: the letter link, the postage and the number of
 * automatic resends left after Gov.Notify fails the letter's validation.
 */
public record LetterJob(UUID notificationId, String letterUrl, boolean firstClass, int resendAttemptsRemaining) {
    public static final String KEY_NOTIFICATION_ID = "notificationId";
    public static final String KEY_LETTER_URL = "letterUrl";
    public static final String KEY_FIRST_CLASS = "firstClass";
    public static final String KEY_RESEND_ATTEMPTS_REMAINING = "resendAttemptsRemaining";

    /** Legacy parity: the {@code Notification} aggregate starts with 5 automatic resends. */
    public static final int RESEND_ATTEMPTS = 5;

    public static LetterJob from(final SendLetterCommand command) {
        return new LetterJob(command.notificationId(), command.letterUrl(), command.isFirstClass(), RESEND_ATTEMPTS);
    }

    public static LetterJob from(final JsonObject jobData) {
        return new LetterJob(
                UUID.fromString(jobData.getString(KEY_NOTIFICATION_ID)),
                jobData.getString(KEY_LETTER_URL),
                jobData.getBoolean(KEY_FIRST_CLASS),
                jobData.getInt(KEY_RESEND_ATTEMPTS_REMAINING));
    }

    public boolean canResend() {
        return resendAttemptsRemaining > 0;
    }

    /** The same letter, sent again, with one fewer automatic resend left. */
    public LetterJob forResend() {
        return new LetterJob(notificationId, letterUrl, firstClass, resendAttemptsRemaining - 1);
    }
}
