package io.kite;

import io.kite.internal.runtime.RunnerCore;

/**
 * Provider-neutral directive that controls how the LLM may use tools on a single chat call.
 *
 * <p>Every major provider exposes equivalent controls; this interface is the normalized shape:
 * <ul>
 *   <li>{@link #auto()} — model decides. Provider default.</li>
 *   <li>{@link #none()} — model may not call any tool.</li>
 *   <li>{@link #required()} — model must call some tool (it picks which). Maps to
 *       OpenAI {@code "required"} and Anthropic {@code "any"}.</li>
 *   <li>{@link #tool(String)} — model must call the named tool.</li>
 *   <li>{@link #route(Agent)} — convenience for forcing a transfer to a specific routed agent.</li>
 * </ul>
 *
 * <p><b>Multi-agent semantics.</b> A {@code ToolChoice} set on an agent applies to every LLM
 * call that agent makes within its own loop. When the agent transfers control via
 * {@code .route(other)}, the <em>target</em> agent's own {@code toolChoice} takes over — each
 * agent owns its directive. Similarly, when an agent is used via {@code asTool()} delegation,
 * the delegated agent runs its own loop with its own settings; the parent's directive does not
 * propagate.
 *
 * <p><b>One-shot semantics.</b> A static {@link Required} or {@link Specific} directive
 * applies until the agent has executed a tool call, then auto-reverts to {@link #auto()} for
 * the remainder of the agent's loop — so the model can synthesize a final text answer once the
 * forced tool has run. After a route transfer the new agent's directive is fresh again.
 * To force a tool on <em>every</em> turn (rare; risks an infinite loop bounded by
 * {@code maxTurns}), use {@code .dynamicToolChoice(Function<T, ToolChoice>)} — dynamic resolvers
 * are re-evaluated every turn and never auto-revert.
 *
 * <p><b>Provider quirks (not enforced by Kite).</b> Anthropic's {@code none} requires
 * Claude 3.5+; {@code required}/{@code Specific} are incompatible with extended thinking mode.
 * Kite does not pre-check these — provider errors will surface.
 */
public sealed interface ToolChoice
        permits ToolChoice.Auto, ToolChoice.None, ToolChoice.Required, ToolChoice.Specific {

    /** Model decides whether and which tool to call. Provider default. */
    static ToolChoice auto() { return Auto.INSTANCE; }

    /** Model may not call any tool. */
    static ToolChoice none() { return None.INSTANCE; }

    /** Model must call some tool (it picks which). */
    static ToolChoice required() { return Required.INSTANCE; }

    /**
     * Model must call the tool with the given name. The name must match either a regular tool
     * on the agent or a synthetic route tool (e.g. {@code transfer_to_billing}).
     */
    static ToolChoice tool(String name) { return new Specific(name); }

    /**
     * Force the model to transfer this conversation to the given routed agent. Equivalent to
     * {@code tool("transfer_to_" + target.name())} but does not leak the naming convention.
     */
    static ToolChoice route(Agent<?> target) {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        return new Specific(RunnerCore.routeToolName(target.name()));
    }

    record Auto() implements ToolChoice {
        static final Auto INSTANCE = new Auto();
    }

    record None() implements ToolChoice {
        static final None INSTANCE = new None();
    }

    record Required() implements ToolChoice {
        static final Required INSTANCE = new Required();
    }

    record Specific(String name) implements ToolChoice {
        public Specific {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("tool choice name must be non-blank");
            }
        }
    }
}
