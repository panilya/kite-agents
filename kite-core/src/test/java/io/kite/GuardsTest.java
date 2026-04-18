package io.kite;

import io.kite.guards.Guard;
import io.kite.guards.GuardDecision;
import io.kite.guards.GuardPhase;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class GuardsTest {

    @Test
    void parallelGuardsShortCircuitOnFirstBlock() {
        var slowFinished = new AtomicBoolean(false);
        var slow = Guard.input("slow").parallel().check(in -> {
            try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            slowFinished.set(true);
            return GuardDecision.allow();
        });
        var fastBlock = Guard.input("fast-block").parallel().check(in -> {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return GuardDecision.block("nope");
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
        var profanityGuard = Guard.output("no-profanity").check(in ->
                in.generatedResponse().contains("badword") ? GuardDecision.block("filtered") : GuardDecision.allow());
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

        var a = Guard.input("a").parallel().check(in -> GuardDecision.allow());
        var b = Guard.input("b").parallel().check(in -> GuardDecision.allow());
        var agent = Agent.builder().model("gpt-test").inputGuards(List.of(a, b)).build();

        kite.run(agent, "hi");

        var names = captured.stream()
                .filter(e -> e instanceof TraceEvent.GuardCheck)
                .map(e -> ((TraceEvent.GuardCheck) e).outcome().name())
                .toList();
        assertThat(names).containsExactlyInAnyOrder("a", "b");
        kite.close();
    }

    @Test
    void parallelGuardRacesLlm() {
        var slowPass = Guard.input("slow-pass").parallel().check(in -> {
            sleepQuiet(400);
            return GuardDecision.allow();
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
        var fastBlock = Guard.input("fast-block").parallel().check(in -> {
            sleepQuiet(50);
            return GuardDecision.block("too scary");
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
        var slowPass = Guard.input("slow-pass").parallel().check(in -> {
            sleepQuiet(300);
            return GuardDecision.allow();
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
        var toolRan = new AtomicBoolean(false);
        var neverShouldRun = Tool.create("should_not_run")
                .description("side effect")
                .execute(args -> { toolRan.set(true); return "ran"; })
                .build();
        var slowBlock = Guard.input("slow-block").parallel().check(in -> {
            sleepQuiet(200);
            return GuardDecision.block("late-block");
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
        var blockingGuard = Guard.input("buf-block").parallel()
                .streamBehavior(StreamBehavior.BUFFER)
                .check(in -> { sleepQuiet(100); return GuardDecision.block("nope"); });
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
        assertThat(events).anyMatch(e -> e instanceof Event.GuardCheck g && g.outcome().blocked());
        assertThat(events).anyMatch(e -> e instanceof Event.Done);
        kite.close();
    }

    @Test
    void streamingBufferFlushesOnPass() {
        var passGuard = Guard.input("buf-pass").parallel()
                .streamBehavior(StreamBehavior.BUFFER)
                .check(in -> { sleepQuiet(50); return GuardDecision.allow(); });
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
    void streamingOutputGuardBlockHidesDeltas() {
        var profanityGuard = Guard.output("no-profanity").check(in ->
                in.generatedResponse().contains("badword") ? GuardDecision.block("filtered") : GuardDecision.allow());
        var mock = MockModelProvider.builder().streamText("this is ", "a badword response").build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test")
                .outputGuards(List.of(profanityGuard))
                .build();

        List<Event> events = new CopyOnWriteArrayList<>();
        kite.stream(agent, "hi", events::add);

        assertThat(events).noneMatch(e -> e instanceof Event.Delta);
        assertThat(events).anyMatch(e ->
                e instanceof Event.GuardCheck g && g.outcome().blocked() && "filtered".equals(g.outcome().message()));
        assertThat(events).anyMatch(e -> e instanceof Event.Done);
        kite.close();
    }

    @Test
    void streamingOutputGuardPassFlushesDeltas() {
        var passGuard = Guard.output("ok").check(in -> GuardDecision.allow());
        var mock = MockModelProvider.builder().streamText("hello ", "world").build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test")
                .outputGuards(List.of(passGuard))
                .build();

        List<Event> events = new CopyOnWriteArrayList<>();
        kite.stream(agent, "hi", events::add);

        var deltas = events.stream()
                .filter(e -> e instanceof Event.Delta)
                .map(e -> ((Event.Delta) e).text())
                .toList();
        assertThat(deltas).containsExactly("hello ", "world");
        assertThat(events).anyMatch(e -> e instanceof Event.Done);
        kite.close();
    }

    @Test
    void streamingOutputGuardOnlyGatesFinalTurn() {
        var badWordGuard = Guard.output("no-bad").check(in ->
                in.generatedResponse().contains("badword") ? GuardDecision.block("filtered") : GuardDecision.allow());
        var echo = Tool.create("echo")
                .description("echo")
                .execute(args -> "ok")
                .build();
        List<ChatChunk> turn1 = new ArrayList<>();
        turn1.add(new ChatChunk.TextDelta("thinking... "));
        turn1.add(new ChatChunk.ToolCallComplete(0, "call-1", "echo", "{}"));
        turn1.add(new ChatChunk.Done(Usage.ZERO, "tool_calls"));
        List<ChatChunk> turn2 = new ArrayList<>();
        turn2.add(new ChatChunk.TextDelta("here's a badword"));
        turn2.add(new ChatChunk.Done(Usage.ZERO, "stop"));
        var mock = MockModelProvider.builder()
                .stream(turn1)
                .stream(turn2)
                .build();
        var kite = Kite.builder().provider(mock).tracing(io.kite.tracing.Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test")
                .tool(echo)
                .outputGuards(List.of(badWordGuard))
                .build();

        List<Event> events = new CopyOnWriteArrayList<>();
        kite.stream(agent, "hi", events::add);

        var deltaTexts = events.stream()
                .filter(e -> e instanceof Event.Delta)
                .map(e -> ((Event.Delta) e).text())
                .toList();
        assertThat(deltaTexts).containsExactly("thinking... ");
        assertThat(events).anyMatch(e ->
                e instanceof Event.GuardCheck g && g.outcome().blocked() && "filtered".equals(g.outcome().message()));
        kite.close();
    }

    @Test
    void streamingPassthroughGatesAfterBlock() {
        var passthroughBlock = Guard.input("pt-block").parallel()
                .streamBehavior(StreamBehavior.PASSTHROUGH)
                .check(in -> { sleepQuiet(10); return GuardDecision.block("nope"); });
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
        assertThat(events).anyMatch(e -> e instanceof Event.GuardCheck g && g.outcome().blocked());
        kite.close();
    }

    @Test
    void cancelledGuardsDoNotLeakTraceEventsAfterRunReturns() {
        var fastBlock = Guard.input("fast-block").parallel().check(in -> {
            sleepQuiet(10);
            return GuardDecision.block("nope");
        });
        var slowPass = Guard.input("slow-pass").parallel().check(in -> {
            sleepQuiet(300);
            return GuardDecision.allow();
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

        sleepQuiet(500);

        var guardNames = captured.stream()
                .filter(e -> e instanceof TraceEvent.GuardCheck)
                .map(e -> ((TraceEvent.GuardCheck) e).outcome().name())
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

        var pass1 = Guard.input("pass1").blocking().check(in -> GuardDecision.allow());
        var pass2 = Guard.input("pass2").blocking().check(in -> GuardDecision.allow());
        var agent = Agent.builder().model("gpt-test").inputGuards(List.of(pass1, pass2)).build();

        kite.run(agent, "hi");

        var guardChecks = captured.stream()
                .filter(e -> e instanceof TraceEvent.GuardCheck)
                .map(e -> (TraceEvent.GuardCheck) e)
                .toList();
        assertThat(guardChecks).hasSize(2);
        assertThat(guardChecks.get(0).outcome().name()).isEqualTo("pass1");
        assertThat(guardChecks.get(0).outcome().blocked()).isFalse();
        assertThat(guardChecks.get(0).outcome().phase()).isEqualTo(GuardPhase.INPUT);
        assertThat(guardChecks.get(1).outcome().name()).isEqualTo("pass2");
        kite.close();
    }
}
