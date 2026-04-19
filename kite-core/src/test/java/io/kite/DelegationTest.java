package io.kite;

import io.kite.annotations.Ctx;
import io.kite.annotations.Tool;
import io.kite.annotations.ToolParam;
import io.kite.guards.Guard;
import io.kite.guards.GuardDecision;
import io.kite.internal.json.JsonCodec;
import io.kite.internal.runtime.MockModelProvider;
import io.kite.model.ChatChunk;
import io.kite.model.ChatResponse;
import io.kite.model.Message;
import io.kite.model.Usage;
import io.kite.tracing.Tracing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the agent-as-tool (delegation) pattern. Covers schema advertisement, result
 * encoding (text / structured / extractor), delegate guard blocks, maxTurns, context
 * propagation (typed + Void), usage accumulation, history isolation, build-time validation,
 * and streaming parent + non-streaming delegate interaction.
 */
class DelegationTest {

    record SupportCtx(String customerId) {}
    record OtherCtx(String x) {}
    record Report(String headline, int confidence) {}

    public static final class EchoCustomer {
        @io.kite.annotations.Tool(description = "Return the caller's customer id")
        public String whoami(@Ctx SupportCtx ctx) {
            return "customer=" + ctx.customerId();
        }
    }

    public static final class Calc {
        @io.kite.annotations.Tool(description = "Add two numbers")
        public int add(@ToolParam int a, @ToolParam int b) {
            return a + b;
        }
    }

    @Test
    void delegateSchemaAdvertisedToParent() {
        var mock = MockModelProvider.builder()
                .respondText("ignored")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var researcher = Agent.builder().model("gpt-test").name("researcher").build();
        var writer = Agent.builder().model("gpt-test").name("writer")
                .tool(researcher.asTool("Research a topic"))
                .build();

        kite.run(writer, "Hi");

        var firstReq = mock.recorded().get(0);
        assertThat(firstReq.tools()).hasSize(1);
        var schema = firstReq.tools().get(0);
        assertThat(schema.name()).isEqualTo("researcher");
        assertThat(schema.description()).isEqualTo("Research a topic");
        assertThat(schema.paramsSchema().writeJson()).contains("\"input\"").contains("\"required\":[\"input\"]");
        kite.close();
    }

    @Test
    void delegateReturnsTextResult_parentIncorporatesIt() {
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "researcher", "{\"input\":\"Webb telescope\"}")
                .respondText("Infrared observatory at L2.")
                .respondText("Done")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var researcher = Agent.builder().model("gpt-test").name("researcher").build();
        var writer = Agent.builder().model("gpt-test").name("writer")
                .tool(researcher.asTool("Research a topic"))
                .build();

        Reply reply = kite.run(writer, "Write a post about Webb");

        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(reply.text()).isEqualTo("Done");
        // recorded[0] = writer's first turn; [1] = delegate's only turn; [2] = writer's follow-up
        assertThat(mock.recorded()).hasSize(3);
        var toolMsg = toolMessageAt(mock, 2);
        assertThat(toolMsg.name()).isEqualTo("researcher");
        assertThat(toolMsg.resultJson()).contains("Infrared observatory at L2.");
        kite.close();
    }

    @Test
    void delegateStructuredOutputEmbeddedAsJsonObject() throws Exception {
        String reportJson = "{\"headline\":\"Webb sees distant galaxies\",\"confidence\":9}";
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "researcher", "{\"input\":\"Webb\"}")
                .respondText(reportJson)
                .respondText("Done")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var researcher = Agent.builder().model("gpt-test").name("researcher")
                .output(Report.class)
                .build();
        var writer = Agent.builder().model("gpt-test").name("writer")
                .tool(researcher.asTool("Research a topic"))
                .build();

        kite.run(writer, "Webb please");

        var node = toolResultAt(mock, 2);
        assertThat(node.has("output")).isTrue();
        assertThat(node.get("output").isObject())
                .as("output should be a nested JSON object, not a stringified JSON")
                .isTrue();
        assertThat(node.get("output").get("headline").asText()).isEqualTo("Webb sees distant galaxies");
        assertThat(node.get("output").get("confidence").asInt()).isEqualTo(9);
        kite.close();
    }

    @Test
    void delegateBlockedByInputGuard_returnedAsToolResult() throws Exception {
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "helper", "{\"input\":\"foo\"}")
                .respondText("Parent handled blocked delegate")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var blocker = Guard.input("no-free").blocking()
                .check(in -> GuardDecision.block(Map.of("message", "please upgrade")));
        var helper = Agent.builder().model("gpt-test").name("helper")
                .inputGuards(List.of(blocker))
                .build();
        var parent = Agent.builder().model("gpt-test").name("parent")
                .tool(helper.asTool("Do stuff"))
                .build();

        Reply reply = kite.run(parent, "anything");

        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(reply.text()).isEqualTo("Parent handled blocked delegate");
        // mock was only called for parent (2 times); delegate never called provider
        assertThat(mock.recorded()).hasSize(2);
        var node = toolResultAt(mock, 1);
        assertThat(node.get("blocked").asBoolean()).isTrue();
        assertThat(node.get("guard").asText()).isEqualTo("no-free");
        assertThat(node.get("info").get("message").asText()).isEqualTo("please upgrade");
        kite.close();
    }

    @Test
    void delegateHitsMaxTurns_returnedAsToolResult() throws Exception {
        var mock = MockModelProvider.builder()
                .respondToolCall("call_d", "helper", "{\"input\":\"x\"}")           // parent #1
                .respondToolCall("call_t", "add", "{\"a\":1,\"b\":2}")                // delegate #1
                .respondText("parent final")                                           // parent #2
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var helper = Agent.builder().model("gpt-test").name("helper")
                .tools(new Calc())
                .maxTurns(1)
                .build();
        var parent = Agent.builder().model("gpt-test").name("parent")
                .tool(helper.asTool("Do stuff"))
                .build();

        Reply reply = kite.run(parent, "start");

        assertThat(reply.status()).isEqualTo(Status.OK);
        var node = toolResultAt(mock, 2);
        assertThat(node.get("max_turns").asBoolean()).isTrue();
        kite.close();
    }

    @Test
    void delegateReceivesParentContext() throws Exception {
        var mock = MockModelProvider.builder()
                .respondToolCall("call_d", "helper", "{\"input\":\"who am I\"}")
                .respondToolCall("call_t", "whoami", "{}")
                .respondText("you are C-42")
                .respondText("done")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var helper = Agent.builder(SupportCtx.class).model("gpt-test").name("helper")
                .tools(new EchoCustomer())
                .build();
        var parent = Agent.builder(SupportCtx.class).model("gpt-test").name("parent")
                .tool(helper.asTool("Identify user"))
                .build();

        Reply reply = kite.run(parent, "hi", new SupportCtx("C-42"));

        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(toolMessageAt(mock, 3).resultJson()).contains("you are C-42");
        kite.close();
    }

    @Test
    void delegateWithVoidContext_buildsAndRuns() {
        var mock = MockModelProvider.builder()
                .respondToolCall("call_d", "helper", "{\"input\":\"x\"}")
                .respondText("ok")
                .respondText("done")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var helper = Agent.builder().model("gpt-test").name("helper").build();
        var parent = Agent.builder(SupportCtx.class).model("gpt-test").name("parent")
                .tool(helper.asTool("Helper"))
                .build();

        Reply reply = kite.run(parent, "hi", new SupportCtx("C-1"));
        assertThat(reply.status()).isEqualTo(Status.OK);
        kite.close();
    }

    @Test
    void delegateUsageAccumulatedIntoParentReplyUsage() {
        var mock = MockModelProvider.builder()
                .respond(new ChatResponse("",
                        List.of(new Message.ToolCallRef("call_d", "helper", "{\"input\":\"x\"}")),
                        new Usage(10, 5, 15, 0.01), "tool_calls", "mock", "r1"))
                .respond(new ChatResponse("delegate text",
                        List.of(),
                        new Usage(4, 2, 6, 0.005), "stop", "mock", "r2"))
                .respond(new ChatResponse("final",
                        List.of(),
                        new Usage(3, 1, 4, 0.001), "stop", "mock", "r3"))
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var helper = Agent.builder().model("gpt-test").name("helper").build();
        var parent = Agent.builder().model("gpt-test").name("parent")
                .tool(helper.asTool("Helper"))
                .build();

        Reply reply = kite.run(parent, "hi");

        assertThat(reply.usage().promptTokens()).isEqualTo(17);
        assertThat(reply.usage().completionTokens()).isEqualTo(8);
        assertThat(reply.usage().totalTokens()).isEqualTo(25);
        assertThat(reply.usage().costUsd()).isEqualTo(0.016);
        kite.close();
    }

    @Test
    void delegateDoesNotPropagateParentHistory() {
        var mock = MockModelProvider.builder()
                .respondToolCall("call_d", "researcher", "{\"input\":\"Webb\"}")
                .respondText("result")
                .respondText("parent final")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var researcher = Agent.builder().model("gpt-test").name("researcher").build();
        var writer = Agent.builder().model("gpt-test").name("writer")
                .tool(researcher.asTool("Research"))
                .build();

        kite.run(writer, "Tell me about Webb");

        var delegateReq = mock.recorded().get(1);
        assertThat(delegateReq.messages()).hasSize(1);
        assertThat(delegateReq.messages().get(0)).isInstanceOf(Message.User.class);
        assertThat(((Message.User) delegateReq.messages().get(0)).content()).isEqualTo("Webb");
        kite.close();
    }

    @Test
    void delegateContextTypeMismatch_failsAtBuild() {
        var other = Agent.builder(OtherCtx.class).model("gpt-test").name("other").build();
        assertThatThrownBy(() -> Agent.builder(SupportCtx.class).model("gpt-test").name("parent")
                .tool(other.asTool("other agent"))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible context type");
    }

    @Test
    void delegateDuplicateToolName_failsAtBuild() {
        var a = Agent.builder().model("gpt-test").name("a").build();
        var b = Agent.builder().model("gpt-test").name("b").build();
        assertThatThrownBy(() -> Agent.builder().model("gpt-test").name("parent")
                .tool(a.asTool("dupe", "one"))
                .tool(b.asTool("dupe", "two"))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate tool name");
    }

    @Test
    void delegateNameWithTransferToPrefix_failsAtBuild() {
        var helper = Agent.builder().model("gpt-test").name("helper").build();
        assertThatThrownBy(() -> Agent.builder().model("gpt-test").name("parent")
                .tool(helper.asTool("transfer_to_foo", "bad name"))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserved prefix");
    }

    @Test
    void customOutputExtractorRunsOnReply() throws Exception {
        var mock = MockModelProvider.builder()
                .respondToolCall("call_d", "helper", "{\"input\":\"x\"}")
                .respondText("full text with extra fluff")
                .respondText("done")
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var helper = Agent.builder().model("gpt-test").name("helper").build();
        var parent = Agent.builder().model("gpt-test").name("parent")
                .tool(helper.asTool("helper", "Helper with extractor", reply -> "CLEAN"))
                .build();

        kite.run(parent, "hi");

        assertThat(toolResultAt(mock, 2).get("output").asText()).isEqualTo("CLEAN");
        kite.close();
    }

    @Test
    void delegateInStreamingParent_emitsToolCallAndToolResultEvents_noInnerDeltas() {
        var mock = MockModelProvider.builder()
                .stream(List.of(
                        new ChatChunk.ToolCallStart(0, "call_d", "helper"),
                        new ChatChunk.ToolCallDelta(0, "{\"input\":\"x\"}"),
                        new ChatChunk.ToolCallComplete(0, "call_d", "helper", "{\"input\":\"x\"}"),
                        new ChatChunk.Done(Usage.ZERO, "tool_calls")))
                // Delegate's non-streaming call uses the chat() script:
                .respondText("delegate result")
                .stream(List.of(
                        new ChatChunk.TextDelta("final"),
                        new ChatChunk.Done(Usage.ZERO, "stop")))
                .build();
        var kite = Kite.builder().provider(mock).tracing(Tracing.off()).build();
        var helper = Agent.builder().model("gpt-test").name("helper").build();
        var parent = Agent.builder().model("gpt-test").name("parent")
                .tool(helper.asTool("Helper"))
                .build();

        List<Event> events = new ArrayList<>();
        kite.stream(parent, "hi", events::add);

        boolean hasToolCall = events.stream().anyMatch(e ->
                e instanceof Event.ToolCall tc && "helper".equals(tc.name()));
        boolean hasToolResult = events.stream().anyMatch(e ->
                e instanceof Event.ToolResult tr && "helper".equals(tr.name()));
        boolean hasDeltaFromDelegate = events.stream().anyMatch(e ->
                e instanceof Event.Delta d && "helper".equals(d.agent()));

        assertThat(hasToolCall).isTrue();
        assertThat(hasToolResult).isTrue();
        assertThat(hasDeltaFromDelegate)
                .as("delegate runs non-streaming; its text must not surface as a Delta on the parent stream")
                .isFalse();
        kite.close();
    }

    private static Message.Tool toolMessageAt(MockModelProvider mock, int requestIndex) {
        return mock.recorded().get(requestIndex).messages().stream()
                .filter(m -> m instanceof Message.Tool)
                .map(m -> (Message.Tool) m)
                .findFirst().orElseThrow();
    }

    private static com.fasterxml.jackson.databind.JsonNode toolResultAt(MockModelProvider mock, int requestIndex) {
        return JsonCodec.shared().readTree(toolMessageAt(mock, requestIndex).resultJson());
    }
}
