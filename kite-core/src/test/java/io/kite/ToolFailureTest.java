package io.kite;

import io.kite.annotations.Tool;
import io.kite.annotations.ToolParam;
import io.kite.internal.runtime.MockModelProvider;
import io.kite.model.ChatChunk;
import io.kite.model.ChatRequest;
import io.kite.model.ChatResponse;
import io.kite.model.Message;
import io.kite.model.ModelProvider;
import io.kite.model.Usage;
import io.kite.tracing.TraceContext;
import io.kite.tracing.TraceEvent;
import io.kite.tracing.TracingProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers every branch of the tool-execution failure path: user throws, null message, timeout,
 * bad args, external interrupt, streaming, delegate failure, unregistered tool name.
 */
class ToolFailureTest {

    /* ============================== Tool beans used by tests ============================== */

    public static final class ThrowingTools {
        @Tool(description = "Always throws")
        public String boom() {
            throw new RuntimeException("db down");
        }
    }

    public static final class NullMessageTools {
        @Tool(description = "Throws NPE with null message")
        public String npe() {
            throw new NullPointerException();
        }
    }

    public static final class TypedArgTools {
        @Tool(description = "Add two ints")
        public int add(@ToolParam int a, @ToolParam int b) {
            return a + b;
        }
    }

    public static final class SlowTools {
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        final CountDownLatch entered = new CountDownLatch(1);

        @Tool(description = "Sleeps forever until interrupted")
        public String slow() {
            entered.countDown();
            try {
                Thread.sleep(60_000);
                return "done";
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        }
    }

    /* ============================== Recording tracer ============================== */

    private static final class RecordingTracer implements TracingProvider {
        final List<TraceEvent> events = new ArrayList<>();

        @Override
        public synchronized void onEvent(TraceContext ctx, TraceEvent event) {
            events.add(event);
        }

        List<TraceEvent.Error> errors() {
            return events.stream()
                    .filter(e -> e instanceof TraceEvent.Error)
                    .map(e -> (TraceEvent.Error) e)
                    .toList();
        }

        List<TraceEvent.ToolResult> toolResults() {
            return events.stream()
                    .filter(e -> e instanceof TraceEvent.ToolResult)
                    .map(e -> (TraceEvent.ToolResult) e)
                    .toList();
        }
    }

    /* ============================== Tests ============================== */

    @Test
    void t1_toolThrows_surfacesStructuredPayloadAndTraceError() {
        var tracer = new RecordingTracer();
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "boom", "{}")
                .respondText("sorry")
                .build();
        var kite = Kite.builder().provider(mock).tracing(tracer).build();
        var agent = Agent.builder().model("gpt-test").tools(new ThrowingTools()).build();

        Reply reply = kite.run(agent, "try the tool");
        assertThat(reply.status()).isEqualTo(Status.OK);

        var toolMsg = toolMessageAt(mock, 1);
        var node = io.kite.internal.json.JsonCodec.shared().readTree(toolMsg.resultJson());
        assertThat(node.get("error").get("type").asText()).isEqualTo("thrown");
        assertThat(node.get("error").get("tool").asText()).isEqualTo("boom");
        assertThat(node.get("error").get("message").asText()).contains("db down");

        assertThat(tracer.errors()).hasSize(1);
        assertThat(tracer.errors().get(0).cause()).isInstanceOf(ToolFailure.ThrownByTool.class);
        kite.close();
    }

    @Test
    void t2_toolThrowsWithNullMessage_payloadCarriesClassName() {
        var tracer = new RecordingTracer();
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "npe", "{}")
                .respondText("ok")
                .build();
        var kite = Kite.builder().provider(mock).tracing(tracer).build();
        var agent = Agent.builder().model("gpt-test").tools(new NullMessageTools()).build();

        kite.run(agent, "go");

        var toolMsg = toolMessageAt(mock, 1);
        var node = io.kite.internal.json.JsonCodec.shared().readTree(toolMsg.resultJson());
        assertThat(node.get("error").get("type").asText()).isEqualTo("thrown");
        assertThat(node.get("error").get("message").asText()).contains("NullPointerException");
        assertThat(node.get("error").get("message").asText()).doesNotContain("tool failed");
        kite.close();
    }

    @Test
    void t3_toolTimesOut_payloadIsTimeoutAndToolInterrupted() throws Exception {
        var tracer = new RecordingTracer();
        var slow = new SlowTools();
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "slow", "{}")
                .respondText("moving on")
                .build();
        var kite = Kite.builder()
                .provider(mock)
                .tracing(tracer)
                .toolTimeout(Duration.ofMillis(100))
                .build();
        var agent = Agent.builder().model("gpt-test").tools(slow).build();

        kite.run(agent, "start");

        var toolMsg = toolMessageAt(mock, 1);
        var node = io.kite.internal.json.JsonCodec.shared().readTree(toolMsg.resultJson());
        assertThat(node.get("error").get("type").asText()).isEqualTo("timeout");
        assertThat(node.get("error").get("tool").asText()).isEqualTo("slow");
        assertThat(tracer.errors()).hasSize(1);

        // The cancelled virtual thread should have observed its interrupt (may race — allow slack).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!slow.interrupted.get() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(slow.interrupted.get()).isTrue();
        kite.close();
    }

    @Test
    void t4_badArgumentBinding_payloadIsBadArguments() {
        var tracer = new RecordingTracer();
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "add", "{\"a\":\"notanumber\",\"b\":2}")
                .respondText("couldn't add")
                .build();
        var kite = Kite.builder().provider(mock).tracing(tracer).build();
        var agent = Agent.builder().model("gpt-test").tools(new TypedArgTools()).build();

        Reply reply = kite.run(agent, "add please");
        assertThat(reply.status()).isEqualTo(Status.OK);

        var toolMsg = toolMessageAt(mock, 1);
        var node = io.kite.internal.json.JsonCodec.shared().readTree(toolMsg.resultJson());
        assertThat(node.get("error").get("type").asText()).isEqualTo("bad_arguments");
        assertThat(node.get("error").get("tool").asText()).isEqualTo("add");
        assertThat(tracer.errors()).hasSize(1);
        kite.close();
    }

    @Test
    void t5_interruptedRunnerAbortsWithRunInterruptedException() throws Exception {
        var tracer = new RecordingTracer();
        var slow = new SlowTools();
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "slow", "{}")
                // Second response never consumed — run must abort before the follow-up LLM call.
                .respondText("unreached")
                .build();
        var kite = Kite.builder().provider(mock).tracing(tracer).build();
        var agent = Agent.builder().model("gpt-test").tools(slow).build();

        var thrown = new AtomicReference<Throwable>();
        var interruptFlagAfter = new AtomicBoolean(false);

        Thread runner = new Thread(() -> {
            try {
                kite.run(agent, "start");
            } catch (Throwable t) {
                thrown.set(t);
                interruptFlagAfter.set(Thread.currentThread().isInterrupted());
            }
        }, "kite-run-t5");
        runner.start();

        assertThat(slow.entered.await(2, TimeUnit.SECONDS)).isTrue();
        runner.interrupt();
        runner.join(2_000);
        assertThat(runner.isAlive()).isFalse();

        assertThat(thrown.get()).isInstanceOf(RunInterruptedException.class);
        assertThat(interruptFlagAfter.get()).isTrue();
        // No tool-result trace event should have been emitted for the aborted call.
        assertThat(tracer.toolResults()).isEmpty();
        kite.close();
    }

    @Test
    void t6_streaming_toolThrows_emitsEventErrorThenToolResult() {
        var mock = MockModelProvider.builder()
                .stream(List.of(
                        new ChatChunk.ToolCallStart(0, "call_1", "boom"),
                        new ChatChunk.ToolCallComplete(0, "call_1", "boom", "{}"),
                        new ChatChunk.Done(Usage.ZERO, "tool_calls")))
                .stream(List.of(
                        new ChatChunk.TextDelta("recovered"),
                        new ChatChunk.Done(Usage.ZERO, "stop")))
                .build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test").tools(new ThrowingTools()).build();

        List<Event> events = new ArrayList<>();
        kite.stream(agent, "go", events::add);

        int errorIdx = indexOf(events, Event.Error.class);
        int resultIdx = indexOf(events, Event.ToolResult.class);
        assertThat(errorIdx).isGreaterThanOrEqualTo(0);
        assertThat(resultIdx).isGreaterThan(errorIdx);

        var errEvent = (Event.Error) events.get(errorIdx);
        assertThat(errEvent.cause()).isInstanceOf(ToolFailure.ThrownByTool.class);

        var resEvent = (Event.ToolResult) events.get(resultIdx);
        assertThat(resEvent.resultJson()).contains("\"type\":\"thrown\"");
        kite.close();
    }

    @Test
    void t7_delegateBubblesRuntimeException_wrappedAsThrownByTool() {
        // Parent #1 calls the delegate. Delegate's own LLM call throws a RuntimeException
        // that no inner handler converts to a ToolFailure (provider itself threw). The
        // RunnerCore's delegate wrap must catch it and re-raise as ThrownByTool so the parent
        // agent sees a normal tool failure instead of the run crashing.
        var tracer = new RecordingTracer();
        var seq = new AtomicInteger(0);
        ModelProvider provider = new ModelProvider() {
            @Override public boolean supports(String m) { return true; }
            @Override public ChatResponse chat(ChatRequest req) {
                int n = seq.getAndIncrement();
                if (n == 0) {
                    return new ChatResponse("",
                            List.of(new Message.ToolCallRef("call_d", "sub", "{\"input\":\"x\"}")),
                            Usage.ZERO, "tool_calls", "m", null);
                }
                if (n == 1) throw new IllegalStateException("provider exploded");
                return new ChatResponse("final", List.of(), Usage.ZERO, "stop", "m", null);
            }
            @Override public void chatStream(ChatRequest req, Consumer<ChatChunk> onChunk) {
                throw new UnsupportedOperationException();
            }
        };
        var kite = Kite.builder().provider(provider).tracing(tracer).build();
        var sub = Agent.builder().model("gpt-test").name("sub").build();
        var parent = Agent.builder().model("gpt-test").name("parent")
                .tool(sub.asTool("Sub-agent"))
                .build();

        Reply reply = kite.run(parent, "go");
        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(reply.text()).isEqualTo("final");

        // The parent sees a Tool message with the delegate's name carrying the structured error.
        var errorEvents = tracer.errors();
        assertThat(errorEvents).hasSize(1);
        assertThat(errorEvents.get(0).cause()).isInstanceOf(ToolFailure.ThrownByTool.class);
        assertThat(((ToolFailure) errorEvents.get(0).cause()).toolName()).isEqualTo("sub");
        kite.close();
    }

    @Test
    void t8_unregisteredToolName_becomesNotRegisteredFailure() {
        var tracer = new RecordingTracer();
        var mock = MockModelProvider.builder()
                .respondToolCall("call_1", "ghost", "{}")
                .respondText("oh well")
                .build();
        var kite = Kite.builder().provider(mock).tracing(tracer).build();
        var agent = Agent.builder().model("gpt-test")
                .tools(new TypedArgTools())
                .build();

        Reply reply = kite.run(agent, "call a nonexistent tool");
        assertThat(reply.status()).isEqualTo(Status.OK);

        var toolMsg = toolMessageAt(mock, 1);
        var node = io.kite.internal.json.JsonCodec.shared().readTree(toolMsg.resultJson());
        assertThat(node.get("error").get("type").asText()).isEqualTo("not_registered");
        assertThat(node.get("error").get("tool").asText()).isEqualTo("ghost");
        assertThat(tracer.errors()).hasSize(1);
        kite.close();
    }

    /* ============================== Helpers ============================== */

    private static Message.Tool toolMessageAt(MockModelProvider mock, int requestIndex) {
        return mock.recorded().get(requestIndex).messages().stream()
                .filter(m -> m instanceof Message.Tool)
                .map(m -> (Message.Tool) m)
                .reduce((a, b) -> b)   // last tool message in that request
                .orElseThrow();
    }

    private static int indexOf(List<Event> events, Class<? extends Event> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) return i;
        }
        return -1;
    }
}
