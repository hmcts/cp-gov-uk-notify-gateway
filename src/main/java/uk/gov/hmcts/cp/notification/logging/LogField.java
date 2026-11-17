package uk.gov.hmcts.cp.notification.logging;

/**
 * The JSON field names on the log lines the alerts count. Each line adds them with SLF4J's
 * {@code addKeyValue}, and the JSON encoder writes them as top-level fields. The Log Analytics alert queries in
 * cp-gov-uk-notify-gateway-terraform-alerts read these names, so renaming one breaks an alert.
 *
 * <p>A call to another service (PEG-3720) carries {@link #DEPENDENCY}, {@link #OPERATION}, {@link #CHANNEL},
 * {@link #OUTCOME}, and {@link #STATUS_CODE} or {@link #REASON} when there is one. A rejected or failed notification
 * (PEG-3718, PEG-3719) carries {@link #REPLY_QUEUE}, {@link #NOTIFICATION_OUTCOME}, {@link #OUTCOME_REASON} and
 * {@link #NOTIFICATION_CHANNEL}; its names differ from the call's, so the two kinds of line never mix in a query.
 */
public final class LogField {
    public static final String DEPENDENCY = "dependency";
    public static final String OPERATION = "operation";
    public static final String CHANNEL = "channel";
    public static final String OUTCOME = "outcome";
    public static final String STATUS_CODE = "statusCode";
    public static final String REASON = "reason";

    public static final String REPLY_QUEUE = "replyQueue";
    public static final String NOTIFICATION_OUTCOME = "notificationOutcome";
    public static final String OUTCOME_REASON = "outcomeReason";
    public static final String NOTIFICATION_CHANNEL = "notificationChannel";

    private LogField() {
    }
}
