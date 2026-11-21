package uk.gov.hmcts.cp.notification.failure;

import static java.util.Collections.newSetFromMap;
import static java.util.List.copyOf;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A failure and every cause under it, so a caller can ask what kind of failure is anywhere in the chain. Stops at a
 * cause it has already seen, so a cyclic chain cannot loop.
 */
public final class CauseChain {
    private final List<Throwable> throwablesInChain;

    public CauseChain(final Throwable failure) {
        final Set<Throwable> visitedThrowables = newSetFromMap(new IdentityHashMap<>());
        final List<Throwable> chain = new ArrayList<>();
        Throwable throwableInChain = failure;
        while (throwableInChain != null && visitedThrowables.add(throwableInChain)) {
            chain.add(throwableInChain);
            throwableInChain = throwableInChain.getCause();
        }
        this.throwablesInChain = copyOf(chain);
    }

    public boolean containsAnyOf(final Set<Class<? extends Throwable>> failureTypes) {
        return throwablesInChain.stream()
                .anyMatch(throwableInChain -> isAnyOf(throwableInChain, failureTypes));
    }

    public boolean containsMatching(final Predicate<Throwable> condition) {
        return throwablesInChain.stream()
                .anyMatch(condition);
    }

    private boolean isAnyOf(final Throwable throwable, final Set<Class<? extends Throwable>> failureTypes) {
        return failureTypes.stream()
                .anyMatch(failureType -> failureType.isInstance(throwable));
    }
}
