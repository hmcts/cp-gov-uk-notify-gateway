package uk.gov.hmcts.cp.notification.messaging.health;

import java.time.ZonedDateTime;

/** What the last attempt to reach a command queue found. */
public sealed interface QueueReachability {

    record NotYetChecked() implements QueueReachability {
    }

    record Reachable(ZonedDateTime reachedAt) implements QueueReachability {
    }

    record Unreachable() implements QueueReachability {
    }
}
