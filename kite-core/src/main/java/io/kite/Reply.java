package io.kite;

import com.fasterxml.jackson.databind.JsonNode;
import io.kite.guards.GuardResults;
import io.kite.internal.json.JsonCodec;
import io.kite.model.Usage;

import java.util.List;

/**
 * Terminal result of {@link Kite#run} or an accumulated summary after {@link Kite#stream}. Guards
 * never throw — a blocked guard produces a Reply with {@code status == BLOCKED}.
 */
public record Reply(
        Status status,
        String text,
        AgentRef agent,
        JsonNode structuredOutput,
        GuardResults guards,
        Usage usage,
        String traceId,
        List<Event> events) {

    public Reply {
        events = events == null ? List.of() : List.copyOf(events);
        usage = usage == null ? Usage.ZERO : usage;
        guards = guards == null ? GuardResults.empty() : guards;
    }

    public boolean blocked() {
        return status == Status.BLOCKED;
    }

    public <T> T output(Class<T> type) {
        if (structuredOutput == null) return null;
        return JsonCodec.shared().treeToValue(structuredOutput, type);
    }

    // Factory helpers used by the runtime.
    public static Reply ok(String text, AgentRef agent, JsonNode structured, GuardResults guards,
                           Usage usage, String traceId, List<Event> events) {
        return new Reply(Status.OK, text, agent, structured, guards, usage, traceId, events);
    }

    public static Reply blocked(GuardResults guards, AgentRef agent, Usage usage, String traceId, List<Event> events) {
        return new Reply(Status.BLOCKED, null, agent, null, guards, usage, traceId, events);
    }

    public static Reply maxTurns(String lastText, AgentRef agent, GuardResults guards, Usage usage,
                                 String traceId, List<Event> events) {
        return new Reply(Status.MAX_TURNS, lastText, agent, null, guards, usage, traceId, events);
    }
}
