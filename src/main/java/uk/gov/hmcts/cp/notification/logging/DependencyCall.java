package uk.gov.hmcts.cp.notification.logging;

import java.util.Optional;

/**
 * The result of one call to a {@link Dependency}, written to a log line as separate JSON fields by
 * {@link DependencyCallLogger} (PEG-3720). {@code statusCode} is empty when the dependency gave none, for example
 * when it could not be reached. Never carries a recipient's details.
 */
public record DependencyCall(
        Dependency dependency,
        Operation operation,
        Channel channel,
        CallOutcome callOutcome,
        Optional<Integer> statusCode) {
}
