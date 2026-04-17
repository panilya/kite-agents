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
 *   <li><b>parallel</b>: runs concurrently with the first-turn LLM call on a virtual thread.
 *       Wall-clock latency is {@code max(guard, llm)} instead of {@code guard + llm}. If the
 *       guard blocks, the in-flight LLM response is discarded and no tools run.</li>
 * </ul>
 *
 * <p>Parallel guards cost tokens on block — the LLM call is already in flight. Use blocking
 * mode if cost matters more than latency. Tools marked
 * {@link ToolBuilder#readOnly(boolean) readOnly} may also start in parallel with a
 * still-running guard (their results are thrown away on block); non-read-only tools never
 * execute until every guard passes.
 *
 * <p>For streaming runs with parallel guards, {@link StreamBehavior} picks the tradeoff:
 * <ul>
 *   <li>{@link StreamBehavior#BUFFER} (default): events are held until the guards resolve.
 *       Nothing leaks downstream on a block; pays the guard wait before the first delta.</li>
 *   <li>{@link StreamBehavior#PASSTHROUGH}: deltas stream live. On a block, downstream stops
 *       receiving further deltas, but already-emitted text stays visible. Tool calls and
 *       handoffs still happen only after guard resolution, so no side effects leak either way.</li>
 * </ul>
 *
 * <p>Output guards always run synchronously after the agent's final response is produced.
 *
 * <p>Client-side guards cannot catch side effects from <em>hosted</em> tools (e.g. built-in
 * web search that a provider runs server-side inside a single API call) — those execute
 * before the response comes back.
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
