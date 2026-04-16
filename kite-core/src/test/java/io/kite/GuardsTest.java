package io.kite;

import io.kite.internal.runtime.MockModelProvider;
import io.kite.tracing.TraceContext;
import io.kite.tracing.TraceEvent;
import io.kite.tracing.TracingProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
