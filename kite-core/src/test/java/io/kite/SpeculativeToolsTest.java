package io.kite;

import io.kite.guards.Guard;
import io.kite.guards.GuardDecision;
import io.kite.internal.runtime.MockModelProvider;
import io.kite.model.Message;
import io.kite.tracing.TraceContext;
import io.kite.tracing.TraceEvent;
import io.kite.tracing.TracingProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SpeculativeToolsTest {

    @Test
    void readOnlyToolExecutesSpeculativelyOnPass() {
        // Slow guard (400ms) + instant LLM returning a read-only tool call + tool that sleeps
        // 200ms: with speculation the tool overlaps the guard wait, so total ≈ max(400, 200) =
        // ~400ms, not 400+200. The follow-up LLM turn is instant.
        var slowPass = Guard.input("slow-pass").parallel().check(in -> {
            sleepQuiet(400);
            return GuardDecision.allow();
        });
        var readOnly = Tool.create("retrieve")
                .description("RAG")
                .readOnly(true)
                .execute(args -> { sleepQuiet(200); return "doc"; })
                .build();
        var mock = MockModelProvider.builder()
                .respondToolCall("call-1", "retrieve", "{}")
                .respondText("final")
                .build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test")
                .inputGuards(List.of(slowPass))
                .tool(readOnly)
                .build();

        Instant start = Instant.now();
        Reply reply = kite.run(agent, "hi");
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(reply.text()).isEqualTo("final");
        // Speculative overlap: well under 400+200=600ms. Allow generous slack for CI.
        assertThat(elapsed).isLessThan(Duration.ofMillis(550));
        kite.close();
    }

    @Test
    void readOnlyToolResultDiscardedOnBlock() {
        // Slow-blocking guard; instant LLM returns a read-only tool call that increments a
        // counter. The tool DOES run (to validate speculation is real), but its result must
        // never reach history or trace, and the Reply must be BLOCKED.
        var invocations = new AtomicInteger();
        var slowBlock = Guard.input("slow-block").parallel().check(in -> {
            sleepQuiet(200);
            return GuardDecision.block("denied");
        });
        var readOnly = Tool.create("retrieve")
                .description("RAG")
                .readOnly(true)
                .execute(args -> { invocations.incrementAndGet(); return "doc"; })
                .build();
        var mock = MockModelProvider.builder()
                .respondToolCall("call-1", "retrieve", "{}")
                .build();
        var captured = new CopyOnWriteArrayList<TraceEvent>();
        TracingProvider capturer = new TracingProvider() {
            @Override public void onEvent(TraceContext ctx, TraceEvent event) { captured.add(event); }
        };
        var kite = Kite.builder().provider(mock).tracing(capturer).build();
        var agent = Agent.builder().model("gpt-test")
                .inputGuards(List.of(slowBlock))
                .tool(readOnly)
                .build();

        Reply reply = kite.run(agent, "hi");

        assertThat(reply.status()).isEqualTo(Status.BLOCKED);
        assertThat(reply.blockReason()).isEqualTo("denied");
        assertThat(invocations).hasValueGreaterThanOrEqualTo(1);  // speculation happened
        assertThat(captured).noneMatch(e -> e instanceof TraceEvent.ToolResult);
        assertThat(captured).noneMatch(e -> e instanceof TraceEvent.ToolCall);
        assertThat(captured).noneMatch(e -> e instanceof TraceEvent.LlmResponse);
        kite.close();
    }

    @Test
    void sideEffectToolWaitsForGuard() {
        // Non-readOnly tool: must not start until the guard passes. We record the timestamp
        // the tool is entered and the timestamp the guard completes; tool_start >= guard_end.
        var guardEnd = new AtomicLong();
        var toolStart = new AtomicLong();
        var slowPass = Guard.input("slow-pass").parallel().check(in -> {
            sleepQuiet(300);
            guardEnd.set(System.nanoTime());
            return GuardDecision.allow();
        });
        var sideEffect = Tool.create("send")
                .description("side effect")
                .execute(args -> { toolStart.set(System.nanoTime()); return "sent"; })
                .build();
        var mock = MockModelProvider.builder()
                .respondToolCall("call-1", "send", "{}")
                .respondText("done")
                .build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test")
                .inputGuards(List.of(slowPass))
                .tool(sideEffect)
                .build();

        Reply reply = kite.run(agent, "hi");

        assertThat(reply.status()).isEqualTo(Status.OK);
        // Tool must have started no earlier than the guard finished.
        assertThat(toolStart.get()).isGreaterThanOrEqualTo(guardEnd.get());
        kite.close();
    }

    @Test
    void mixedBatchPreservesHistoryOrder() {
        // LLM emits [A readOnly, B side-effect, C readOnly] in one batch. Regardless of which
        // speculative future finishes first, the Tool messages in history must appear in the
        // original LLM-emitted order A, B, C.
        var passGuard = Guard.input("pass").parallel().check(in -> {
            sleepQuiet(100);
            return GuardDecision.allow();
        });
        // A finishes last (slow), C finishes first (fast) — if we ordered by completion we'd
        // see C first, which is wrong.
        var toolA = Tool.create("read_a").description("RAG A").readOnly(true)
                .execute(args -> { sleepQuiet(150); return "A"; })
                .build();
        var toolB = Tool.create("side_b").description("side B")
                .execute(args -> "B")
                .build();
        var toolC = Tool.create("read_c").description("RAG C").readOnly(true)
                .execute(args -> "C")
                .build();

        var calls = new Message.ToolCallRef[]{
                new Message.ToolCallRef("id-A", "read_a", "{}"),
                new Message.ToolCallRef("id-B", "side_b", "{}"),
                new Message.ToolCallRef("id-C", "read_c", "{}")
        };
        var mock = MockModelProvider.builder()
                .respondToolCalls(calls)
                .respondText("final")
                .build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test")
                .inputGuards(List.of(passGuard))
                .tool(toolA).tool(toolB).tool(toolC)
                .build();

        Reply reply = kite.run(agent, "hi");
        assertThat(reply.status()).isEqualTo(Status.OK);

        // Inspect the second chat request — its history should contain Tool messages in order
        // A, B, C directly after the assistant's tool-call batch.
        var req = mock.recorded().get(1);
        var toolMsgs = req.messages().stream()
                .filter(m -> m instanceof Message.Tool)
                .map(m -> ((Message.Tool) m).name())
                .toList();
        assertThat(toolMsgs).containsExactly("read_a", "side_b", "read_c");
        kite.close();
    }

    @Test
    void readOnlyToolFailurePropagatedOnPass() {
        // Read-only tool throws — on guard pass, the error must appear in trace and history
        // just like a serially-executed tool failure would.
        var passGuard = Guard.input("pass").parallel().check(in -> GuardDecision.allow());
        var boom = Tool.create("boom").description("boom").readOnly(true)
                .execute(args -> { throw new RuntimeException("kaboom"); })
                .build();
        var mock = MockModelProvider.builder()
                .respondToolCall("call-1", "boom", "{}")
                .respondText("recovered")
                .build();
        var captured = new CopyOnWriteArrayList<TraceEvent>();
        TracingProvider capturer = new TracingProvider() {
            @Override public void onEvent(TraceContext ctx, TraceEvent event) { captured.add(event); }
        };
        var kite = Kite.builder().provider(mock).tracing(capturer).build();
        var agent = Agent.builder().model("gpt-test")
                .inputGuards(List.of(passGuard))
                .tool(boom)
                .build();

        Reply reply = kite.run(agent, "hi");
        assertThat(reply.status()).isEqualTo(Status.OK);

        assertThat(captured).anyMatch(e -> e instanceof TraceEvent.Error err
                && err.message() != null && err.message().contains("kaboom"));

        // History should contain a Tool message with an error payload for id-1.
        var secondReq = mock.recorded().get(1);
        var toolMsg = secondReq.messages().stream()
                .filter(m -> m instanceof Message.Tool t && "call-1".equals(t.toolCallId()))
                .map(m -> ((Message.Tool) m).resultJson())
                .findFirst().orElseThrow();
        assertThat(toolMsg).contains("\"error\"").contains("kaboom");
        kite.close();
    }

    @Test
    void speculativeToolTimeoutHonored() {
        // Read-only tool that sleeps past the configured toolTimeout. On guard pass, the
        // timeout must surface as a structured error in history, same as a serial tool.
        var passGuard = Guard.input("pass").parallel().check(in -> GuardDecision.allow());
        var slow = Tool.create("slow").description("slow").readOnly(true)
                .execute(args -> { sleepQuiet(2000); return "late"; })
                .build();
        var mock = MockModelProvider.builder()
                .respondToolCall("call-1", "slow", "{}")
                .respondText("recovered")
                .build();
        var kite = Kite.builder()
                .provider(mock)
                .tracing(io.kite.tracing.Tracing.off())
                .toolTimeout(Duration.ofMillis(100))
                .build();
        var agent = Agent.builder().model("gpt-test")
                .inputGuards(List.of(passGuard))
                .tool(slow)
                .build();

        Reply reply = kite.run(agent, "hi");
        assertThat(reply.status()).isEqualTo(Status.OK);

        var secondReq = mock.recorded().get(1);
        var toolMsg = secondReq.messages().stream()
                .filter(m -> m instanceof Message.Tool t && "call-1".equals(t.toolCallId()))
                .map(m -> ((Message.Tool) m).resultJson())
                .findFirst().orElseThrow();
        assertThat(toolMsg).contains("\"error\"").containsIgnoringCase("timed out");
        kite.close();
    }

    @Test
    void readOnlyAnnotationRoundTrips() {
        // @Tool(readOnly=true/false) on a scanned bean must produce Tool instances whose
        // readOnly() flag matches the annotation. Locks in the ToolInvokerFactory wiring.
        var agent = Agent.builder().model("gpt-test").tools(new AnnotatedBean()).build();
        var readOnly = agent.tools().stream()
                .filter(t -> t.name().equals("retrieve")).findFirst().orElseThrow();
        var sideEffect = agent.tools().stream()
                .filter(t -> t.name().equals("send")).findFirst().orElseThrow();
        var defaulted = agent.tools().stream()
                .filter(t -> t.name().equals("compute")).findFirst().orElseThrow();

        assertThat(readOnly.readOnly()).isTrue();
        assertThat(sideEffect.readOnly()).isFalse();
        assertThat(defaulted.readOnly()).isFalse();  // flag defaults to false when unset
    }

    /** Bean with a mix of annotated tools — used by {@link #readOnlyAnnotationRoundTrips()}. */
    public static final class AnnotatedBean {
        @io.kite.annotations.Tool(name = "retrieve", description = "read docs", readOnly = true)
        public String retrieve(@io.kite.annotations.ToolParam(name = "q") String q) { return "ok"; }

        @io.kite.annotations.Tool(name = "send", description = "send mail", readOnly = false)
        public String send(@io.kite.annotations.ToolParam(name = "to") String to) { return "sent"; }

        @io.kite.annotations.Tool(name = "compute", description = "compute")
        public String compute(@io.kite.annotations.ToolParam(name = "x") int x) { return String.valueOf(x); }
    }

    private static void sleepQuiet(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
