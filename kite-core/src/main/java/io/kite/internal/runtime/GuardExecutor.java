package io.kite.internal.runtime;

import io.kite.Guard;
import io.kite.GuardResult;
import io.kite.Guards;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/**
 * Runs before/after guards. Blocking guards run inline on the caller thread; parallel guards
 * fan out onto the virtual-thread executor via {@link CompletableFuture}.
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
     * Run all parallel guards concurrently. Returns as soon as any guard blocks — the remaining
     * guards continue running on the executor but their results are ignored. If all guards pass,
     * returns a pass result after the last completes.
     */
    public <T> GuardResult runParallel(List<Guard<T>> guards, Object ctx, String subject,
                                       BiConsumer<Guard<T>, GuardResult> observer) {
        var parallelOnly = guards.stream().filter(g -> g.mode() == Guard.Mode.PARALLEL).toList();
        if (parallelOnly.isEmpty()) return GuardResult.pass();

        var firstBlock = new CompletableFuture<GuardResult>();
        List<CompletableFuture<GuardResult>> futures = new ArrayList<>(parallelOnly.size());
        for (var g : parallelOnly) {
            var f = CompletableFuture.supplyAsync(() -> safeCheck(g, ctx, subject), vexec);
            f.thenAccept(r -> {
                observer.accept(g, r);
                if (r.blocked()) firstBlock.complete(r);
            });
            futures.add(f);
        }
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(CompletableFuture[]::new));
        CompletableFuture<GuardResult> allPassed = allDone.thenApply(v -> GuardResult.pass());

        @SuppressWarnings("unchecked")
        var either = (CompletableFuture<GuardResult>) (CompletableFuture<?>)
                CompletableFuture.anyOf(firstBlock, allPassed);
        return either.join();
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
