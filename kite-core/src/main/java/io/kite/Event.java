package io.kite;

import java.time.Duration;

/**
 * Events emitted during streaming execution. {@code agent()} identifies the agent that produced
 * the event; for {@link Transfer} it is the agent being transferred from.
 */
public sealed interface Event
        permits Event.Delta,
                Event.ToolCall,
                Event.ToolResult,
                Event.Transfer,
                Event.Blocked,
                Event.Done,
                Event.Error {

    String agent();

    record Delta(String agent, String text) implements Event {}

    record ToolCall(String agent, String name, String argsJson) implements Event {}

    record ToolResult(String agent, String name, String resultJson, Duration elapsed) implements Event {}

    record Transfer(String from, String to) implements Event {
        @Override public String agent() { return from; }
    }

    record Blocked(String agent, String guard, String message) implements Event {}

    record Done(String agent, Reply reply) implements Event {}

    record Error(String agent, Throwable cause) implements Event {}
}
