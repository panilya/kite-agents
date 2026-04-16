package io.kite;

/**
 * Failure raised while executing a tool call on behalf of an agent. The runner catches these
 * and feeds a structured error payload back to the LLM instead of aborting the run.
 *
 * <p>Subclasses carry a stable {@link #kind()} string that also appears in the JSON surfaced to
 * the model, letting the LLM branch on the failure type without parsing freeform English.
 */
public abstract sealed class ToolFailure extends RuntimeException
        permits ToolFailure.Timeout,
                ToolFailure.ThrownByTool,
                ToolFailure.BadArguments,
                ToolFailure.NotRegistered {

    private final String toolName;

    private ToolFailure(String toolName, String message, Throwable cause) {
        super(message, cause);
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }

    /** Stable identifier surfaced to the LLM as {@code error.type}. */
    public abstract String kind();

    /** The tool call exceeded the runner's configured {@code toolTimeout}. */
    public static final class Timeout extends ToolFailure {
        public Timeout(String toolName, String message, Throwable cause) {
            super(toolName, message, cause);
        }
        @Override public String kind() { return "timeout"; }
    }

    /** User-written tool code threw. {@link #getCause()} is the original throwable. */
    public static final class ThrownByTool extends ToolFailure {
        public ThrownByTool(String toolName, String message, Throwable cause) {
            super(toolName, message, cause);
        }
        @Override public String kind() { return "thrown"; }
    }

    /** The model supplied arguments that failed JSON parsing or type binding. */
    public static final class BadArguments extends ToolFailure {
        public BadArguments(String toolName, String message, Throwable cause) {
            super(toolName, message, cause);
        }
        @Override public String kind() { return "bad_arguments"; }
    }

    /** The model called a tool name not registered on the current agent. */
    public static final class NotRegistered extends ToolFailure {
        public NotRegistered(String toolName, String message) {
            super(toolName, message, null);
        }
        @Override public String kind() { return "not_registered"; }
    }
}
