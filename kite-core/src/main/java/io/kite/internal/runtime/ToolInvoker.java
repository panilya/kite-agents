package io.kite.internal.runtime;

/**
 * Internal runtime type — the compiled invocation path for a single tool. Created once at
 * {@code AgentBuilder.build()} time and reused for the lifetime of the agent.
 *
 * <p>Implementations wrap either a method handle bound to a user bean or a lambda from
 * {@code ToolBuilder}. Neither reflection nor schema lookups run at call time; everything is
 * pre-resolved.
 */
@FunctionalInterface
public interface ToolInvoker {

    /**
     * Invoke the tool. {@code context} is the current agent context (may be null for Void
     * agents or when the tool does not use context). {@code argsJson} is the JSON-encoded
     * arguments string produced by the LLM. The return value is already serialized to JSON
     * so the runner can embed it in the conversation history verbatim.
     */
    String invoke(Object context, String argsJson) throws Exception;
}
