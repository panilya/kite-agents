package io.kite.internal.runtime;

import io.kite.guards.Guard;
import io.kite.guards.GuardDecision;
import io.kite.guards.GuardInput;
import io.kite.guards.GuardOutcome;
import io.kite.guards.InputGuard;
import io.kite.guards.InputGuardInput;
import io.kite.guards.OutputGuard;
import io.kite.guards.OutputGuardInput;
import io.kite.StreamBehavior;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Runs before/after guards. Blocking guards run inline on the caller thread; parallel guards
 * fan out onto the virtual-thread executor via {@link CompletableFuture} and are handed back to
 * the caller as a {@link ParallelGuardHandle} so they can race the main LLM call.
 */
public final class GuardExecutor {

    private final ExecutorService vexec;

    public GuardExecutor(ExecutorService vexec) {
        this.vexec = vexec;
    }

    /**
     * Run all blocking input guards inline. Stops at the first blocking outcome. Returns every
     * outcome produced so the caller can accumulate the full history into the reply.
     * {@code observer} fires once per guard as it completes.
     */
    public <T> List<GuardOutcome> runBlocking(List<InputGuard<T>> guards,
                                              InputGuardInput<T> input,
                                              Consumer<GuardOutcome> observer) {
        List<GuardOutcome> outcomes = new ArrayList<>();
        for (var g : guards) {
            if (g.mode() != InputGuard.Mode.BLOCKING) continue;
            GuardOutcome o = safeCheck(g, input);
            observer.accept(o);
            outcomes.add(o);
            if (o.blocked()) return outcomes;
        }
        return outcomes;
    }

    /**
     * Start all parallel input guards on the virtual-thread executor and return a live handle.
     * The caller races {@link ParallelGuardHandle#firstBlock()} against the LLM call: if some
     * guard blocks first, the in-flight LLM response is discarded; if the LLM finishes first,
     * the caller joins {@link ParallelGuardHandle#allOutcomes()} to collect the remaining
     * outcomes before committing.
     *
     * <p>The observer is chained into the same stage that {@code allOf} awaits, so when
     * {@link ParallelGuardHandle#allOutcomes()} resolves on the all-pass path every observer
     * has already fired — preserving event ordering. On the {@code firstBlock} short-circuit
     * path, only the blocking guard's observer is guaranteed to have fired; still-running
     * guards may emit trace events after the run returns.
     */
    public <T> ParallelGuardHandle startParallel(List<InputGuard<T>> guards,
                                                 InputGuardInput<T> input,
                                                 Consumer<GuardOutcome> observer) {
        var parallelOnly = guards.stream()
                .filter(g -> g.mode() == InputGuard.Mode.PARALLEL)
                .toList();
        if (parallelOnly.isEmpty()) return ParallelGuardHandle.empty();

        var firstBlock = new CompletableFuture<GuardOutcome>();
        List<CompletableFuture<GuardOutcome>> futures = new ArrayList<>(parallelOnly.size());
        boolean anyBuffer = false;
        for (var g : parallelOnly) {
            var f = CompletableFuture.supplyAsync(() -> safeCheck(g, input), vexec)
                    .thenApply(o -> {
                        observer.accept(o);
                        if (o.blocked()) firstBlock.complete(o);
                        return o;
                    });
            futures.add(f);
            if (g.streamBehavior() == StreamBehavior.BUFFER) anyBuffer = true;
        }
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(CompletableFuture[]::new));
        CompletableFuture<List<GuardOutcome>> allOutcomes = allDone.thenApply(v -> {
            List<GuardOutcome> collected = new ArrayList<>(futures.size());
            for (var f : futures) collected.add(f.join());
            return collected;
        });

        return new ParallelGuardHandle(firstBlock, allOutcomes, anyBuffer, List.copyOf(futures));
    }

    /**
     * Run output guards sequentially. Stops at the first blocking outcome. Returns every
     * outcome produced.
     */
    public <T> List<GuardOutcome> runAfter(List<OutputGuard<T>> guards,
                                           OutputGuardInput<T> input,
                                           Consumer<GuardOutcome> observer) {
        List<GuardOutcome> outcomes = new ArrayList<>();
        for (var g : guards) {
            GuardOutcome o = safeCheck(g, input);
            observer.accept(o);
            outcomes.add(o);
            if (o.blocked()) return outcomes;
        }
        return outcomes;
    }

    private GuardOutcome safeCheck(Guard<?> g, GuardInput<?> input) {
        Instant start = Instant.now();
        GuardDecision decision;
        try {
            decision = g.decide(input);
            if (decision == null) {
                decision = GuardDecision.block(Map.of("message", "Guard '" + g.name() + "' returned null"));
            }
        } catch (Throwable t) {
            decision = GuardDecision.block(Map.of("message", "Guard '" + g.name() + "' threw: " + t.getMessage()));
        }
        return new GuardOutcome(g.name(), g.phase(), decision, Duration.between(start, Instant.now()));
    }
}
