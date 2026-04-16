package io.kite;

/**
 * Signals that the thread driving a run was interrupted while a tool call was in flight. The
 * runner does NOT feed this back to the LLM — interrupting the driver thread means the whole run
 * should abort, per {@link InterruptedException}'s contract.
 *
 * <p>Deliberately not a {@link ToolFailure}: callers that catch {@code ToolFailure} to recover
 * from tool-level errors must not swallow a cooperative cancellation.
 */
public final class RunInterruptedException extends RuntimeException {

    public RunInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
