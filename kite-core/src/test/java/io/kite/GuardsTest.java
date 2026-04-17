package io.kite;

import io.kite.internal.runtime.MockModelProvider;
import io.kite.model.ChatChunk;
import io.kite.model.Usage;
import io.kite.tracing.TraceContext;
import io.kite.tracing.TraceEvent;
import io.kite.tracing.TracingProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GuardsTest {

    @Test
    void parallelGuardsShortCircuitOnFirstBlock() throws InterruptedException {
        // A fast guard blocks at ~10ms; a slow guard would otherwise wait ~5s. We assert that
        // the run returns much sooner than 5s — proving the short-circuit on first block.
        var slowFinished = new AtomicBoolean(false);
        var slow = Guard.input("slow").parallel().check((ctx, in) -> {
            try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            slowFinished.set(true);
            return Guard.pass();
        });
        var fastBlock = Guard.input("fast-block").parallel().check((ctx, in) -> {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Guard.block("nope");
        });

        var mock = MockModelProvider.builder().build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test")
                .inputGuards(List.of(slow, fastBlock))
                .build();

        Instant start = Instant.now();
        Reply reply = kite.run(agent, "hi");
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(reply.status()).isEqualTo(Status.BLOCKED);
        assertThat(reply.blockReason()).isEqualTo("nope");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        assertThat(slowFinished).isFalse();
        kite.close();
    }

    @Test
    void outputGuardBlocksFinalReply() {
        var mock = MockModelProvider.builder()
                .respondText("contains badword in here")
                .build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var profanityGuard = Guard.output("no-profanity").check((ctx, out) ->
                out.contains("badword") ? Guard.block("filtered") : Guard.pass());
        var agent = Agent.builder().model("gpt-test")
                .outputGuards(List.of(profanityGuard))
                .build();

        Reply reply = kite.run(agent, "hi");
        assertThat(reply.status()).isEqualTo(Status.BLOCKED);
        assertThat(reply.blockReason()).isEqualTo("filtered");
        kite.close();
    }

    @Test
    void parallelGuardTraceEventsEmittedBeforeRunReturns() {
        // Regression for the observer-ordering race: `allOf` used to wait on the supplier
        // futures only, not on the thenAccept stages that called the observer. A slow observer
        // exposes the gap — with the old code, kite.run returns while the GuardCheck events
        // are still in flight and `captured` is missing entries.
        var captured = new CopyOnWriteArrayList<TraceEvent>();
        TracingProvider slowCapturer = new TracingProvider() {
            @Override public void onEvent(TraceContext ctx, TraceEvent event) {
                if (event instanceof TraceEvent.GuardCheck) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                captured.add(event);
            }
        };
        var mock = MockModelProvider.builder().respondText("ok").build();
        var kite = Kite.builder().provider(mock).tracing(slowCapturer).build();

        var a = Guard.input("a").parallel().check((ctx, in) -> Guard.pass());
        var b = Guard.input("b").parallel().check((ctx, in) -> Guard.pass());
        var agent = Agent.builder().model("gpt-test").inputGuards(List.of(a, b)).build();

        kite.run(agent, "hi");

        var names = captured.stream()
                .filter(e -> e instanceof TraceEvent.GuardCheck)
                .map(e -> ((TraceEvent.GuardCheck) e).guard())
                .toList();
        assertThat(names).containsExactlyInAnyOrder("a", "b");
        kite.close();
    }

    @Test
    void parallelGuardRacesLlm() {
        // Both guard and LLM take 400ms. If they truly race on the virtual-thread executor the
        // wall-clock is ~400ms; if they still run serially it would be ~800ms.
        var slowPass = Guard.input("slow-pass").parallel().check((ctx, in) -> {
            sleepQuiet(400);
            return Guard.pass();
        });
        var mock = MockModelProvider.builder()
                .withLatency(Duration.ofMillis(400))
                .respondText("ok")
                .build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test").inputGuards(List.of(slowPass)).build();

        Instant start = Instant.now();
        Reply reply = kite.run(agent, "hi");
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(elapsed).isLessThan(Duration.ofMillis(700));
        kite.close();
    }

    @Test
    void parallelGuardBlockDuringLlmInFlight() {
        // LLM would take 2s; guard blocks at ~50ms. Run should return BLOCKED well under 500ms,
        // and the LLM response must never be committed — no LlmResponse trace event, usage ZERO.
        var fastBlock = Guard.input("fast-block").parallel().check((ctx, in) -> {
            sleepQuiet(50);
            return Guard.block("too scary");
        });
        var mock = MockModelProvider.builder()
                .withLatency(Duration.ofSeconds(2))
                .respondText("should-never-be-seen")
                .build();
        var captured = new CopyOnWriteArrayList<TraceEvent>();
        TracingProvider capturer = new TracingProvider() {
            @Override public void onEvent(TraceContext ctx, TraceEvent event) { captured.add(event); }
        };
        var kite = Kite.builder().provider(mock).tracing(capturer).build();
        var agent = Agent.builder().model("gpt-test").inputGuards(List.of(fastBlock)).build();

        Instant start = Instant.now();
        Reply reply = kite.run(agent, "hi");
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(reply.status()).isEqualTo(Status.BLOCKED);
        assertThat(reply.blockReason()).isEqualTo("too scary");
        assertThat(elapsed).isLessThan(Duration.ofMillis(500));
        assertThat(reply.usage()).isEqualTo(Usage.ZERO);
        assertThat(captured).noneMatch(e -> e instanceof TraceEvent.LlmResponse);
        kite.close();
    }

    @Test
    void parallelGuardLlmFasterThanGuard() {
        // LLM is instant; guard takes 300ms. Wall-clock should be ≈ max(guard, llm) = 300ms,
        // not llm+guard. We must NOT reveal the LLM response until the guard resolves.
        var slowPass = Guard.input("slow-pass").parallel().check((ctx, in) -> {
            sleepQuiet(300);
            return Guard.pass();
        });
        var mock = MockModelProvider.builder().respondText("fast").build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test").inputGuards(List.of(slowPass)).build();

        Instant start = Instant.now();
        Reply reply = kite.run(agent, "hi");
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(reply.text()).isEqualTo("fast");
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(250));
        assertThat(elapsed).isLessThan(Duration.ofMillis(600));
        kite.close();
    }

    @Test
    void parallelGuardLlmFasterThanGuardThatBlocks() {
        // LLM returns tool calls instantly; guard blocks at 200ms. Even though the LLM "won"
        // the race, the guard's late block must still discard the response and prevent tool
        // execution entirely.
        var toolRan = new AtomicBoolean(false);
        var neverShouldRun = Tool.create("should_not_run")
                .description("side effect")
                .execute(args -> { toolRan.set(true); return "ran"; })
                .build();
        var slowBlock = Guard.input("slow-block").parallel().check((ctx, in) -> {
            sleepQuiet(200);
            return Guard.block("late-block");
        });
        var mock = MockModelProvider.builder()
                .respondToolCall("call-1", "should_not_run", "{}")
                .build();
        var captured = new CopyOnWriteArrayList<TraceEvent>();
        TracingProvider capturer = new TracingProvider() {
            @Override public void onEvent(TraceContext ctx, TraceEvent event) { captured.add(event); }
        };
        var kite = Kite.builder().provider(mock).tracing(capturer).build();
        var agent = Agent.builder().model("gpt-test")
                .inputGuards(List.of(slowBlock))
                .tool(neverShouldRun)
                .build();

        Reply reply = kite.run(agent, "hi");

        assertThat(reply.status()).isEqualTo(Status.BLOCKED);
        assertThat(reply.blockReason()).isEqualTo("late-block");
        assertThat(toolRan).isFalse();
        assertThat(captured).noneMatch(e -> e instanceof TraceEvent.LlmResponse);
        assertThat(captured).noneMatch(e -> e instanceof TraceEvent.ToolCall);
        kite.close();
    }

    @Test
    void streamingBufferHoldsDeltasUntilGuardResolves() {
        // With a BUFFER-mode parallel guard that blocks, downstream must not see any Delta
        // events — only Blocked and Done.
        var blockingGuard = Guard.input("buf-block").parallel()
                .streamBehavior(StreamBehavior.BUFFER)
                .check((ctx, in) -> { sleepQuiet(100); return Guard.block("nope"); });
        List<ChatChunk> chunks = new ArrayList<>();
        chunks.add(new ChatChunk.TextDelta("hello "));
        chunks.add(new ChatChunk.TextDelta("world"));
        chunks.add(new ChatChunk.Done(Usage.ZERO, "stop"));
        var mock = MockModelProvider.builder().stream(chunks).build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test").inputGuards(List.of(blockingGuard)).build();

        List<Event> events = new CopyOnWriteArrayList<>();
        kite.stream(agent, "hi", events::add);

        assertThat(events).noneMatch(e -> e instanceof Event.Delta);
        assertThat(events).anyMatch(e -> e instanceof Event.Blocked);
        assertThat(events).anyMatch(e -> e instanceof Event.Done);
        kite.close();
    }

    @Test
    void streamingBufferFlushesOnPass() {
        // All-pass BUFFER guards: downstream eventually sees the held Delta events.
        var passGuard = Guard.input("buf-pass").parallel()
                .streamBehavior(StreamBehavior.BUFFER)
                .check((ctx, in) -> { sleepQuiet(50); return Guard.pass(); });
        List<ChatChunk> chunks = new ArrayList<>();
        chunks.add(new ChatChunk.TextDelta("abc"));
        chunks.add(new ChatChunk.Done(Usage.ZERO, "stop"));
        var mock = MockModelProvider.builder().stream(chunks).build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test").inputGuards(List.of(passGuard)).build();

        List<Event> events = new CopyOnWriteArrayList<>();
        kite.stream(agent, "hi", events::add);

        assertThat(events).anyMatch(e -> e instanceof Event.Delta d && d.text().equals("abc"));
        assertThat(events).anyMatch(e -> e instanceof Event.Done);
        kite.close();
    }

    @Test
    void streamingPassthroughGatesAfterBlock() {
        // PASSTHROUGH: deltas that arrive before the block may reach downstream, but once the
        // guard blocks, no further events leak. Here the guard blocks very quickly (10ms) while
        // the stream pauses 200ms before first delta, so no delta should be visible.
        var passthroughBlock = Guard.input("pt-block").parallel()
                .streamBehavior(StreamBehavior.PASSTHROUGH)
                .check((ctx, in) -> { sleepQuiet(10); return Guard.block("nope"); });
        List<ChatChunk> chunks = new ArrayList<>();
        chunks.add(new ChatChunk.TextDelta("late"));
        chunks.add(new ChatChunk.Done(Usage.ZERO, "stop"));
        var mock = MockModelProvider.builder()
                .withLatency(Duration.ofMillis(200))
                .stream(chunks)
                .build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test").inputGuards(List.of(passthroughBlock)).build();

        List<Event> events = new CopyOnWriteArrayList<>();
        kite.stream(agent, "hi", events::add);

        assertThat(events).noneMatch(e -> e instanceof Event.Delta);
        assertThat(events).anyMatch(e -> e instanceof Event.Blocked);
        kite.close();
    }

    @Test
    void cancelledGuardsDoNotLeakTraceEventsAfterRunReturns() {
        // Regression: before the cancelAll() fix, a slow parallel guard from run N would emit
        // its GuardCheck trace event AFTER the run returned — polluting run N+1's trace. The
        // fix cancels the observer stage so the event never fires. Here we run once with a
        // fast-block + a slow-pass, then wait long enough for the slow guard's underlying
        // supplier to finish, and assert no extra GuardCheck event arrived.
        var fastBlock = Guard.input("fast-block").parallel().check((ctx, in) -> {
            sleepQuiet(10);
            return Guard.block("nope");
        });
        var slowPass = Guard.input("slow-pass").parallel().check((ctx, in) -> {
            sleepQuiet(300);
            return Guard.pass();
        });
        var captured = new CopyOnWriteArrayList<TraceEvent>();
        TracingProvider capturer = new TracingProvider() {
            @Override public void onEvent(TraceContext ctx, TraceEvent event) { captured.add(event); }
        };
        var mock = MockModelProvider.builder().build();
        var kite = Kite.builder().provider(mock).tracing(capturer).build();
        var agent = Agent.builder().model("gpt-test")
                .inputGuards(List.of(fastBlock, slowPass))
                .build();

        Reply reply = kite.run(agent, "hi");
        assertThat(reply.status()).isEqualTo(Status.BLOCKED);

        // Wait past slow-pass's 300ms sleep — its observer must not fire.
        sleepQuiet(500);

        var guardNames = captured.stream()
                .filter(e -> e instanceof TraceEvent.GuardCheck)
                .map(e -> ((TraceEvent.GuardCheck) e).guard())
                .toList();
        assertThat(guardNames).containsExactly("fast-block");
        kite.close();
    }

    private static void sleepQuiet(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test
    void guardCheckTraceEventEmittedForEachGuard() {
        var captured = new CopyOnWriteArrayList<TraceEvent>();
        TracingProvider capturer = new TracingProvider() {
            @Override public void onEvent(TraceContext ctx, TraceEvent event) { captured.add(event); }
        };
        var mock = MockModelProvider.builder().respondText("ok").build();
        var kite = Kite.builder().provider(mock).tracing(capturer).build();

        var pass1 = Guard.input("pass1").blocking().check((ctx, in) -> Guard.pass());
        var pass2 = Guard.input("pass2").blocking().check((ctx, in) -> Guard.pass());
        var agent = Agent.builder().model("gpt-test").inputGuards(List.of(pass1, pass2)).build();

        kite.run(agent, "hi");

        var guardChecks = captured.stream()
                .filter(e -> e instanceof TraceEvent.GuardCheck)
                .map(e -> (TraceEvent.GuardCheck) e)
                .toList();
        assertThat(guardChecks).hasSize(2);
        assertThat(guardChecks.get(0).guard()).isEqualTo("pass1");
        assertThat(guardChecks.get(0).passed()).isTrue();
        assertThat(guardChecks.get(0).phase()).isEqualTo("INPUT");
        assertThat(guardChecks.get(1).guard()).isEqualTo("pass2");
        kite.close();
    }
}
