package io.kite;

/**
 * Internal accessor that lets {@code io.kite.internal.runtime} invoke package-private
 * methods on {@link Guard} without exposing them on the public API. Not for application use.
 */
public final class Guards {
    private Guards() {}

    public static GuardResult run(Guard<?> guard, Object context, String subject) {
        return guard.check(context, subject);
    }
}
