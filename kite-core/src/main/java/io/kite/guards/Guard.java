package io.kite.guards;

/**
 * A validation check run before (input guards) or after (output guards) an agent executes.
 * Guards never throw — a blocked guard produces a {@link Reply} with {@code status == BLOCKED}.
 *
 * <p>{@code Guard} is a sealed interface with two record implementations, {@link InputGuard}
 * and {@link OutputGuard}, built via {@link #input(String)} / {@link #output(String)}. The
 * phase is structural: input guards run before the first LLM call; output guards run on the
 * final assistant text after every tool call has resolved.
 *
 * <p><b>Input guards</b> pick an execution mode:
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
 * <p>For streaming runs with parallel input guards, {@link StreamBehavior} picks the tradeoff:
 * <ul>
 *   <li>{@link StreamBehavior#BUFFER} (default): events are held until the guards resolve.
 *       Nothing leaks downstream on a block; pays the guard wait before the first delta.</li>
 *   <li>{@link StreamBehavior#PASSTHROUGH}: deltas stream live. On a block, downstream stops
 *       receiving further deltas, but already-emitted text stays visible. Tool calls and
 *       handoffs still happen only after guard resolution, so no side effects leak either way.</li>
 * </ul>
 *
 * <p><b>Output guards</b> always run sequentially, in declaration order, after the agent's
 * final response (the turn with no tool calls) is fully assembled. In streaming mode the
 * run waits for the complete response before checking, and text deltas of the final turn
 * are buffered until the guard passes — a block discards the deltas so nothing reaches the
 * caller. Parallel/streaming-behavior knobs do not apply to output guards.
 *
 * <p>Client-side guards cannot catch side effects from <em>hosted</em> tools (e.g. built-in
 * web search that a provider runs server-side inside a single API call) — those execute
 * before the response comes back.
 */
public sealed interface Guard<T> permits InputGuard, OutputGuard {

    String name();

    GuardPhase phase();

    /**
     * Internal — invoked by the runtime only. The unchecked cast inside each record
     * implementation is safe because the runtime always hands a phase-matched
     * {@link GuardInput} subtype.
     */
    GuardDecision decide(GuardInput<?> input);

    /**
     * Start building an input guard. The type parameter is the agent's context type; call without
     * a witness for untyped agents ({@code Guard.input("x")}), or pass one for typed agents
     * ({@code Guard.<UserCtx>input("x")}).
     */
    static <T> InputGuardBuilder<T> input(String name) {
        return new InputGuardBuilder<>(name);
    }

    /**
     * Start building an output guard. The type parameter is the agent's context type; call without
     * a witness for untyped agents, or pass one for typed agents.
     */
    static <T> OutputGuardBuilder<T> output(String name) {
        return new OutputGuardBuilder<>(name);
    }
}
