package io.kite.guards;

import java.util.List;
import java.util.stream.Stream;

/**
 * Container for every guard check that ran during a {@link Reply}: the two lists are in
 * declaration order by phase. Exposes helpers for the common "did anything block?" query.
 */
public record GuardResults(
        List<GuardOutcome> input,
        List<GuardOutcome> output
) {

    public GuardResults {
        input = input == null ? List.of() : List.copyOf(input);
        output = output == null ? List.of() : List.copyOf(output);
    }

    public static GuardResults empty() {
        return new GuardResults(List.of(), List.of());
    }

    /** The first blocking outcome across input-then-output, or null when nothing blocked. */
    public GuardOutcome blocking() {
        return Stream.concat(input.stream(), output.stream())
                .filter(GuardOutcome::blocked)
                .findFirst()
                .orElse(null);
    }

    public boolean anyBlocked() {
        return blocking() != null;
    }
}
