package io.kite;

import io.kite.internal.runtime.MockModelProvider;
import io.kite.tracing.TraceContext;
import io.kite.tracing.TraceEvent;
import io.kite.tracing.Tracing;
import io.kite.tracing.TracingProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingValidationTest {

    @Test
    void duplicateRouteNamesRejectedAtBuild() {
        var billingA = Agent.builder().model("gpt-test").name("billing").build();
        var billingB = Agent.builder().model("gpt-test").name("billing").build();
        assertThatThrownBy(() -> Agent.builder().model("gpt-test").name("triage")
                .route(billingA)
                .route(billingB)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate route target name 'billing'");
    }

    @Test
    void siblingToolCallsDuringRouteSurfaceObservableEvents() {
        // Mock LLM emits two tool calls in one turn: a route + a function call.
        // The function call must NOT execute, but observers must SEE that it was skipped.
        var mock = MockModelProvider.builder()
                .respond(new io.kite.model.ChatResponse("",
                        List.of(
                                new io.kite.model.Message.ToolCallRef("call_route", "transfer_to_billing", "{}"),
                                new io.kite.model.Message.ToolCallRef("call_refund", "refund", "{\"orderId\":\"#1\"}")),
                        io.kite.model.Usage.ZERO, "tool_calls", "mock", "r1"))
                .respondText("billing handled")
                .build();

        var captured = new CopyOnWriteArrayList<TraceEvent>();
        TracingProvider capturer = new TracingProvider() {
            @Override public void onEvent(TraceContext ctx, TraceEvent event) { captured.add(event); }
        };
        var kite = Kite.builder().provider(mock).tracing(capturer).build();

        var billing = Agent.builder().model("gpt-test").name("billing").description("Billing").build();
        var triage = Agent.builder().model("gpt-test").name("triage")
                .tool(Tool.create("refund")
                        .description("Process a refund")
                        .param("orderId", String.class, "Order id")
                        .execute(args -> { throw new AssertionError("refund must not execute"); })
                        .build())
                .route(billing)
                .build();

        kite.run(triage, "I want a refund and please transfer me");

        // Trace must include a ToolResult for the dropped refund call carrying the skipped marker.
        var skippedResults = captured.stream()
                .filter(e -> e instanceof TraceEvent.ToolResult)
                .map(e -> (TraceEvent.ToolResult) e)
                .filter(r -> "refund".equals(r.toolName()))
                .toList();
        assertThat(skippedResults).hasSize(1);
        assertThat(skippedResults.get(0).resultJson()).contains("\"skipped\":true");
        kite.close();
    }

    @Test
    void siblingToolCallsDuringRouteSurfaceOnStreamingPath() {
        var mock = MockModelProvider.builder()
                .stream(List.of(
                        new io.kite.model.ChatChunk.ToolCallStart(0, "call_route", "transfer_to_billing"),
                        new io.kite.model.ChatChunk.ToolCallComplete(0, "call_route", "transfer_to_billing", "{}"),
                        new io.kite.model.ChatChunk.ToolCallStart(1, "call_refund", "refund"),
                        new io.kite.model.ChatChunk.ToolCallComplete(1, "call_refund", "refund", "{\"orderId\":\"#1\"}"),
                        new io.kite.model.ChatChunk.Done(io.kite.model.Usage.ZERO, "tool_calls")))
                .stream(List.of(
                        new io.kite.model.ChatChunk.TextDelta("billing handled"),
                        new io.kite.model.ChatChunk.Done(io.kite.model.Usage.ZERO, "stop")))
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();

        var billing = Agent.builder().model("gpt-test").name("billing").description("Billing").build();
        var triage = Agent.builder().model("gpt-test").name("triage")
                .tool(Tool.create("refund")
                        .description("Process a refund")
                        .param("orderId", String.class, "Order id")
                        .execute(args -> { throw new AssertionError("refund must not execute"); })
                        .build())
                .route(billing)
                .build();

        List<Event> events = new ArrayList<>();
        kite.stream(triage, "transfer + refund", events::add);

        var skippedToolResults = events.stream()
                .filter(e -> e instanceof Event.ToolResult)
                .map(e -> (Event.ToolResult) e)
                .filter(r -> "refund".equals(r.name()))
                .toList();
        assertThat(skippedToolResults)
                .as("dropped sibling refund must surface as a ToolResult event with the skipped marker")
                .hasSize(1);
        assertThat(skippedToolResults.get(0).resultJson()).contains("\"skipped\":true");
        kite.close();
    }
}
