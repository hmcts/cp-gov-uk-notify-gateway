package uk.gov.hmcts.cp.notification.messaging.health;

import java.time.ZonedDateTime;

import static java.util.Objects.requireNonNullElse;

/** The outcome of the last attempt to reach a queue. */
public sealed interface QueueReachability {

    record NotYetChecked() implements QueueReachability {
    }

    record Reachable(ZonedDateTime checkedAt) implements QueueReachability {
    }

    record Unreachable(ZonedDateTime checkedAt, RuntimeException peekFailure) implements QueueReachability {

        /** What went wrong, in words: the failure's message, or its type when it has no message. */
        public String peekFailureDescription() {
            return requireNonNullElse(peekFailure.getMessage(), peekFailure.getClass().getName());
        }
    }
}
