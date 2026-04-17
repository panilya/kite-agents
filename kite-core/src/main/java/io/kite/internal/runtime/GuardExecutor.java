package io.kite.internal.runtime;

import io.kite.Guard;
import io.kite.GuardResult;
import io.kite.Guards;
import io.kite.StreamBehavior;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

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
     * Run all blocking before-guards inline. Returns the first blocking result, or a pass result
     * if every guard passes. {@code observer} is invoked once per guard with its individual
     * result (used by {@link RunnerCore} to emit {@code TraceEvent.GuardCheck}).
     */
    public <T> GuardResult runBlocking(List<Guard<T>> guards, Object ctx, String subject,
                                       BiConsumer<Guard<T>, GuardResult> observer) {
        for (var g : guards) {
            if (g.mode() != Guard.Mode.BLOCKING) continue;
            GuardResult r = safeCheck(g, ctx, subject);
            observer.accept(g, r);
            if (r.blocked()) return r;
        }
        return GuardResult.pass();
    }

    /**
     * Start all parallel guards on the virtual-thread executor and return a live handle. The
     * caller races {@link ParallelGuardHandle#firstBlock()} against the LLM call: if some guard
     * blocks first, the in-flight LLM response is discarded; if the LLM finishes first, the
     * caller joins {@link ParallelGuardHandle#completion()} to wait for the remaining guards
     * before committing the response.
     *
     * <p>The observer is chained into the same stage that {@code allOf} awaits, so when
     * {@link ParallelGuardHandle#completion()} resolves on the all-pass path every observer
     * has already fired — preserving {@code TraceEvent.GuardCheck} ordering. On the
     * {@code firstBlock} short-circuit path, only the blocking guard's observer is guaranteed
     * to have fired; still-running guards may emit trace events after the run returns.
     */
    public <T> ParallelGuardHandle startParallel(List<Guard<T>> guards, Object ctx, String subject,
                                                 BiConsumer<Guard<T>, GuardResult> observer) {
        var parallelOnly = guards.stream().filter(g -> g.mode() == Guard.Mode.PARALLEL).toList();
        if (parallelOnly.isEmpty()) return ParallelGuardHandle.empty();

        var firstBlock = new CompletableFuture<GuardResult>();
        List<CompletableFuture<GuardResult>> futures = new ArrayList<>(parallelOnly.size());
        boolean anyBuffer = false;
        for (var g : parallelOnly) {
            var f = CompletableFuture.supplyAsync(() -> safeCheck(g, ctx, subject), vexec)
                    .thenApply(r -> {
                        observer.accept(g, r);
                        if (r.blocked()) firstBlock.complete(r);
                        return r;
                    });
            futures.add(f);
            if (g.streamBehavior() == StreamBehavior.BUFFER) anyBuffer = true;
        }
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(CompletableFuture[]::new));
        CompletableFuture<GuardResult> allPassed = allDone.thenApply(v -> GuardResult.pass());

        @SuppressWarnings("unchecked")
        var completion = (CompletableFuture<GuardResult>) (CompletableFuture<?>)
                CompletableFuture.anyOf(firstBlock, allPassed);

        return new ParallelGuardHandle(firstBlock, completion, anyBuffer, List.copyOf(futures));
    }

    public <T> GuardResult runAfter(List<Guard<T>> guards, Object ctx, String subject,
                                    BiConsumer<Guard<T>, GuardResult> observer) {
        for (var g : guards) {
            GuardResult r = safeCheck(g, ctx, subject);
            observer.accept(g, r);
            if (r.blocked()) return r;
        }
        return GuardResult.pass();
    }

    private <T> GuardResult safeCheck(Guard<T> g, Object ctx, String subject) {
        try {
            return Guards.run(g, ctx, subject);
        } catch (Throwable t) {
            return GuardResult.block("Guard '" + g.name() + "' threw: " + t.getMessage())
                    .withMetadata(g.name(),
                            g.phase() == Guard.Phase.INPUT ? GuardResult.Phase.INPUT : GuardResult.Phase.OUTPUT,
                            subject);
        }
    }
}
