package io.kite;

import java.util.function.BiFunction;

/**
 * A validation check run before (input guards) or after (output guards) an agent executes.
 * Guards never throw — a blocked guard produces a {@link Reply} with {@code status == BLOCKED}.
 *
 * <p>Two execution modes for input guards:
 * <ul>
 *   <li><b>blocking</b> (default): runs to completion before the LLM call starts. If it blocks,
 *       zero tokens are consumed.</li>
 *   <li><b>parallel</b>: runs concurrently with the LLM call on a virtual thread. Lower latency
 *       in the common case; if the guard blocks, the in-flight LLM response is discarded.</li>
 * </ul>
 *
 * <p>Output guards always run synchronously after the agent's final response is produced.
 */
public final class Guard<T> {

    public enum Phase { INPUT, OUTPUT }

    public enum Mode { BLOCKING, PARALLEL }

    private final String name;
    private final Phase phase;
    private final Mode mode;
    private final StreamBehavior streamBehavior;
    private final BiFunction<T, String, GuardResult> check;

    Guard(String name,
          Phase phase,
          Mode mode,
          StreamBehavior streamBehavior,
          BiFunction<T, String, GuardResult> check) {
        this.name = name;
        this.phase = phase;
        this.mode = mode;
        this.streamBehavior = streamBehavior;
        this.check = check;
    }

    public String name() { return name; }
    public Phase phase() { return phase; }
    public Mode mode() { return mode; }
    public StreamBehavior streamBehavior() { return streamBehavior; }

    /**
     * Internal — invoked by the runtime only. The unchecked cast is safe because the runtime
     * resolves {@code context} from the agent's typed context (or {@code null} for {@code Void}).
     */
    @SuppressWarnings("unchecked")
    GuardResult check(Object context, String subject) {
        GuardResult r = check.apply((T) context, subject);
        return r.withMetadata(name, phase == Phase.INPUT ? GuardResult.Phase.INPUT : GuardResult.Phase.OUTPUT, subject);
    }

    public static InputGuardBuilder<Void> input(String name) {
        return new InputGuardBuilder<>(name);
    }

    @SuppressWarnings("unused")
    public static <T> InputGuardBuilder<T> inputTyped(String name) {
        return new InputGuardBuilder<>(name);
    }

    public static OutputGuardBuilder<Void> output(String name) {
        return new OutputGuardBuilder<>(name);
    }

    @SuppressWarnings("unused")
    public static <T> OutputGuardBuilder<T> outputTyped(String name) {
        return new OutputGuardBuilder<>(name);
    }

    public static GuardResult pass() {
        return GuardResult.pass();
    }

    public static GuardResult block(String message) {
        return GuardResult.block(message);
    }
}
