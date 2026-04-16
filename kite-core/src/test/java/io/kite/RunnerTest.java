package io.kite;

import io.kite.annotations.Ctx;
import io.kite.annotations.Tool;
import io.kite.annotations.ToolParam;
import io.kite.internal.runtime.MockModelProvider;
import io.kite.model.ChatChunk;
import io.kite.model.Message;
import io.kite.model.Usage;
import io.kite.tracing.Tracing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests covering both runLoop and streamLoop via MockModelProvider. These exercise
 * the shared RunnerCore and verify that run/stream produce equivalent results on identical
 * scripted provider responses.
 */
class RunnerTest {

    record SupportCtx(String customerId) {}

    public static final class Calculator {
        @Tool(description = "Add two numbers")
        public int add(@ToolParam int a, @ToolParam int b) {
            return a + b;
        }
    }

    public static final class BillingTools {
        @Tool(description = "Refund an order")
        public String refund(
                @Ctx SupportCtx ctx,
                @ToolParam(description = "Order id") String orderId) {
            return "refunded " + orderId + " for " + ctx.customerId();
        }
    }

    @Test
    void singleTurnNonStreaming() {
        var mock = MockModelProvider.builder()
                .respondText("Hello, world!")
                .build();
        var kite = Kite.builder()
                .provider(mock)
                .tracing(Tracing.off())
                .build();
        var agent = Agent.of("gpt-test")
                .name("greeter")
                .instructions("You are a greeter.")
                .build();

        Reply reply = kite.run(agent, "Hi");
        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(reply.text()).isEqualTo("Hello, world!");
        assertThat(mock.recorded()).hasSize(1);
        assertThat(mock.lastRequest().stream()).isFalse();
        kite.close();
    }

    @Test
    void singleTurnStreaming() {
        var mock = MockModelProvider.builder()
                .streamText("Hello", ", ", "world", "!")
                .build();
        var kite = Kite.builder()
                .provider(mock)
                .tracing(Tracing.off())
                .build();
        var agent = Agent.of("gpt-test")
                .name("greeter")
                .instructions("You are a greeter.")
                .build();

        List<Event> events = new ArrayList<>();
        kite.stream(agent, "Hi", events::add);

        long deltas = events.stream().filter(e -> e instanceof Event.Delta).count();
        assertThat(deltas).isEqualTo(4);
        Event.Done done = (Event.Done) events.get(events.size() - 1);
        assertThat(done.reply().text()).isEqualTo("Hello, world!");
        assertThat(done.reply().status()).isEqualTo(Status.OK);
        assertThat(mock.lastRequest().stream()).isTrue();
        kite.close();
    }

    @Test
    void multiTurnWithToolCallNonStreaming() {
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "add", "{\"a\":2,\"b\":3}")
                .respondText("The sum is 5")
                .build();
        var kite = Kite.builder()
                .provider(mock)
                .tracing(Tracing.off())
                .build();
        var agent = Agent.of("gpt-test")
                .instructions("Use the add tool when asked for a sum.")
                .tools(new Calculator())
                .build();

        Reply reply = kite.run(agent, "What is 2 + 3?");
        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(reply.text()).isEqualTo("The sum is 5");
        // Two LLM calls: the tool call, then the follow-up.
        assertThat(mock.recorded()).hasSize(2);
        // Second call's history must include the tool result.
        var secondReq = mock.recorded().get(1);
        boolean hasToolMessage = secondReq.messages().stream()
                .anyMatch(m -> m instanceof io.kite.model.Message.Tool);
        assertThat(hasToolMessage).isTrue();
        kite.close();
    }

    @Test
    void multiTurnWithToolCallStreaming() {
        var mock = MockModelProvider.builder()
                .stream(List.of(
                        new ChatChunk.ToolCallStart(0, "call_1", "add"),
                        new ChatChunk.ToolCallDelta(0, "{\"a\":2,\"b\":3}"),
                        new ChatChunk.ToolCallComplete(0, "call_1", "add", "{\"a\":2,\"b\":3}"),
                        new ChatChunk.Done(Usage.ZERO, "tool_calls")))
                .stream(List.of(
                        new ChatChunk.TextDelta("The sum is 5"),
                        new ChatChunk.Done(Usage.ZERO, "stop")))
                .build();
        var kite = Kite.builder()
                .provider(mock)
                .tracing(Tracing.off())
                .build();
        var agent = Agent.of("gpt-test")
                .tools(new Calculator())
                .build();

        List<Event> events = new ArrayList<>();
        kite.stream(agent, "Sum 2 and 3", events::add);

        boolean hasToolCall = events.stream().anyMatch(e -> e instanceof Event.ToolCall);
        boolean hasToolResult = events.stream().anyMatch(e -> e instanceof Event.ToolResult);
        assertThat(hasToolCall).isTrue();
        assertThat(hasToolResult).isTrue();
        Event.Done done = (Event.Done) events.get(events.size() - 1);
        assertThat(done.reply().text()).isEqualTo("The sum is 5");
        kite.close();
    }

    @Test
    void typedContextFlowsToTool() {
        var bean = new BillingTools();
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "refund", "{\"orderId\":\"#99\"}")
                .respondText("Done")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var agent = Agent.of("gpt-test", SupportCtx.class)
                .tools(bean)
                .build();

        Reply reply = kite.run(agent, "refund order 99", new SupportCtx("C-42"));
        assertThat(reply.status()).isEqualTo(Status.OK);
        kite.close();
    }

    @Test
    void blockingGuardShortCircuitsBeforeLlmCall() {
        var mock = MockModelProvider.builder().build();   // no scripted response — LLM must not be called
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var block = Guard.input("block-all")
                .blocking()
                .check((ctx, input) -> Guard.block("nope"));
        var agent = Agent.of("gpt-test")
                .before(block)
                .build();

        Reply reply = kite.run(agent, "anything");
        assertThat(reply.status()).isEqualTo(Status.BLOCKED);
        assertThat(reply.blockReason()).isEqualTo("nope");
        assertThat(mock.recorded()).isEmpty();
        kite.close();
    }

    @Test
    void routingTransfersToTargetAgent() {
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "transfer_to_billing", "{}")
                .respondText("Billing handled it")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var billing = Agent.of("gpt-test").name("billing").description("Billing specialist").build();
        var triage = Agent.of("gpt-test")
                .name("triage")
                .route(billing)
                .build();

        Reply reply = kite.run(triage, "I have a billing question");
        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(reply.text()).isEqualTo("Billing handled it");
        assertThat(reply.agent().name()).isEqualTo("billing");
        kite.close();
    }

    @Test
    void routingAppendsSyntheticToolOutputsForHistoryPairing() {
        var mock = MockModelProvider.builder()
                .respondToolCall("call_route", "transfer_to_billing", "{}")
                .respondText("Billing handled it")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var billing = Agent.of("gpt-test").name("billing").description("Billing specialist").build();
        var triage = Agent.of("gpt-test").name("triage").route(billing).build();

        kite.run(triage, "I have a billing question");

        // Second request (billing's first turn) must see paired history: the routed
        // function_call in the preceding Assistant message must have a matching Message.Tool.
        var secondReq = mock.recorded().get(1);
        assertPairedToolCalls(secondReq.messages());
        boolean routePaired = secondReq.messages().stream()
                .filter(m -> m instanceof Message.Tool)
                .map(m -> ((Message.Tool) m).toolCallId())
                .anyMatch("call_route"::equals);
        assertThat(routePaired).isTrue();
        kite.close();
    }

    @Test
    void multiHopRoutingKeepsHistoryPaired() {
        var mock = MockModelProvider.builder()
                .respondToolCall("call_a", "transfer_to_billing", "{}")
                .respondToolCall("call_b", "transfer_to_audit", "{}")
                .respondText("Audited")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var audit = Agent.of("gpt-test").name("audit").build();
        var billing = Agent.of("gpt-test").name("billing").route(audit).build();
        var triage = Agent.of("gpt-test").name("triage").route(billing).build();

        kite.run(triage, "complex case");

        // Every recorded request after the first must be well-formed on every hop.
        for (int i = 1; i < mock.recorded().size(); i++) {
            assertPairedToolCalls(mock.recorded().get(i).messages());
        }
        kite.close();
    }

    @Test
    void routingKeepsHistoryPairedWhenStreaming() {
        var mock = MockModelProvider.builder()
                .stream(List.of(
                        new ChatChunk.ToolCallStart(0, "call_route", "transfer_to_billing"),
                        new ChatChunk.ToolCallDelta(0, "{}"),
                        new ChatChunk.ToolCallComplete(0, "call_route", "transfer_to_billing", "{}"),
                        new ChatChunk.Done(Usage.ZERO, "tool_calls")))
                .stream(List.of(
                        new ChatChunk.TextDelta("Billing handled it"),
                        new ChatChunk.Done(Usage.ZERO, "stop")))
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var billing = Agent.of("gpt-test").name("billing").description("Billing specialist").build();
        var triage = Agent.of("gpt-test").name("triage").route(billing).build();

        List<Event> events = new ArrayList<>();
        kite.stream(triage, "I have a billing question", events::add);

        boolean hasTransfer = events.stream().anyMatch(e -> e instanceof Event.Transfer);
        assertThat(hasTransfer).isTrue();

        var secondReq = mock.recorded().get(1);
        assertPairedToolCalls(secondReq.messages());
        kite.close();
    }

    @Test
    void toolChoiceStaticAppliedOnFirstRequestOnly() {
        // Static Required/Specific is one-shot: it forces a tool call on the first request, then
        // auto-reverts so the model can synthesize a final text answer instead of looping.
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "add", "{\"a\":1,\"b\":2}")
                .respondText("3")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var agent = Agent.of("gpt-test")
                .tools(new Calculator())
                .toolChoice(ToolChoice.required())
                .build();

        kite.run(agent, "sum");

        assertThat(mock.recorded()).hasSize(2);
        assertThat(mock.recorded().get(0).toolChoice()).isEqualTo(ToolChoice.required());
        assertThat(mock.recorded().get(1).toolChoice()).isEqualTo(ToolChoice.auto());
        kite.close();
    }

    @Test
    void toolChoiceDynamicReappliedEveryTurn() {
        // Dynamic resolvers bypass the one-shot rule — user has full control. Returning Required
        // every turn is a valid (if unusual) way to force a tool call on every iteration.
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "add", "{\"a\":1,\"b\":2}")
                .respondText("3")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var agent = Agent.of("gpt-test")
                .tools(new Calculator())
                .toolChoice((Void ctx) -> ToolChoice.required())
                .build();

        kite.run(agent, "sum");

        assertThat(mock.recorded()).hasSize(2);
        assertThat(mock.recorded().get(0).toolChoice()).isEqualTo(ToolChoice.required());
        assertThat(mock.recorded().get(1).toolChoice()).isEqualTo(ToolChoice.required());
        kite.close();
    }

    @Test
    void toolChoiceDynamicResolvesFromContext() {
        var mock = MockModelProvider.builder()
                .respondText("ok")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var agent = Agent.of("gpt-test", SupportCtx.class)
                .tools(new BillingTools())
                .toolChoice((SupportCtx ctx) ->
                        "vip".equals(ctx.customerId()) ? ToolChoice.required() : ToolChoice.auto())
                .build();

        kite.run(agent, "anything", new SupportCtx("vip"));
        assertThat(mock.recorded().get(0).toolChoice()).isEqualTo(ToolChoice.required());

        var mock2 = MockModelProvider.builder().respondText("ok").build();
        var kite2 = Kite.builder().provider(mock2).tracing(Tracing.off()).build();
        var agent2 = Agent.of("gpt-test", SupportCtx.class)
                .tools(new BillingTools())
                .toolChoice((SupportCtx ctx) ->
                        "vip".equals(ctx.customerId()) ? ToolChoice.required() : ToolChoice.auto())
                .build();

        kite2.run(agent2, "anything", new SupportCtx("regular"));
        assertThat(mock2.recorded().get(0).toolChoice()).isEqualTo(ToolChoice.auto());
        kite.close();
        kite2.close();
    }

    @Test
    void toolChoicePersistsAcrossRouteTransfer() {
        // Source agent forces a route; target agent forces a specific function tool.
        // After the transfer, request 2+ must carry the TARGET's directive, not the source's.
        var mock = MockModelProvider.builder()
                .respondToolCall("call_route", "transfer_to_billing", "{}")
                .respondToolCall("call_refund", "refund", "{\"orderId\":\"#1\"}")
                .respondText("done")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var billing = Agent.of("gpt-test", SupportCtx.class)
                .name("billing")
                .description("Billing specialist")
                .tools(new BillingTools())
                .toolChoice(ToolChoice.tool("refund"))
                .build();
        var triage = Agent.of("gpt-test", SupportCtx.class)
                .name("triage")
                .route(billing)
                .toolChoice(ToolChoice.route(billing))
                .build();

        kite.run(triage, "I want a refund for order 1", new SupportCtx("C-42"));

        assertThat(mock.recorded()).hasSize(3);
        assertThat(mock.recorded().get(0).toolChoice())
                .as("request 1 = triage: must force routing to billing")
                .isEqualTo(new ToolChoice.Specific("transfer_to_billing"));
        assertThat(mock.recorded().get(1).toolChoice())
                .as("request 2 = billing after transfer: must carry billing's own directive (fresh on transfer)")
                .isEqualTo(new ToolChoice.Specific("refund"));
        assertThat(mock.recorded().get(2).toolChoice())
                .as("request 3 = billing follow-up: directive reverted to auto after refund executed")
                .isEqualTo(ToolChoice.auto());
        kite.close();
    }

    @Test
    void toolChoiceSpecificUnknownToolFailsAtBuild() {
        assertThatThrownBy(() -> Agent.of("gpt-test")
                .tools(new Calculator())
                .toolChoice(ToolChoice.tool("nope"))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void toolChoiceDynamicUnknownToolFailsAtRequestTime() {
        var mock = MockModelProvider.builder().build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var agent = Agent.of("gpt-test", SupportCtx.class)
                .tools(new BillingTools())
                .toolChoice((SupportCtx ctx) -> ToolChoice.tool("not_there"))
                .build();

        assertThatThrownBy(() -> kite.run(agent, "anything", new SupportCtx("C-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not_there")
                .hasMessageContaining("refund");
        kite.close();
    }

    @Test
    void parallelToolCallsFlagForwarded() {
        var mock = MockModelProvider.builder().respondText("ok").build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var agent = Agent.of("gpt-test")
                .tools(new Calculator())
                .parallelToolCalls(false)
                .build();

        kite.run(agent, "hello");
        assertThat(mock.recorded().get(0).parallelToolCalls()).isFalse();
        kite.close();
    }

    @Test
    void toolChoiceCarriesThroughStreamingPath() {
        var mock = MockModelProvider.builder()
                .stream(List.of(
                        new ChatChunk.TextDelta("hi"),
                        new ChatChunk.Done(Usage.ZERO, "stop")))
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var agent = Agent.of("gpt-test")
                .tools(new Calculator())
                .toolChoice(ToolChoice.required())
                .build();

        kite.stream(agent, "hello", ev -> {});
        assertThat(mock.recorded().get(0).toolChoice()).isEqualTo(ToolChoice.required());
        kite.close();
    }

    private static void assertPairedToolCalls(List<Message> msgs) {
        LinkedHashSet<String> openCalls = new LinkedHashSet<>();
        for (var m : msgs) {
            if (m instanceof Message.Assistant a) {
                for (var call : a.toolCalls()) openCalls.add(call.id());
            } else if (m instanceof Message.Tool t) {
                openCalls.remove(t.toolCallId());
            }
        }
        assertThat(openCalls)
                .as("every Assistant toolCall must have a matching Message.Tool")
                .isEmpty();
    }
}
