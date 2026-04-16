package io.kite.internal.runtime;

import io.kite.Guard;
import io.kite.GuardResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

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
     * if every guard passes.
     */
    public <T> GuardResult runBlocking(List<Guard<T>> guards, Object ctx, String subject) {
        for (var g : guards) {
            if (g.mode() != Guard.Mode.BLOCKING) continue;
            GuardResult r = safeCheck(g, ctx, subject);
            if (r.blocked()) return r;
        }
        return GuardResult.pass();
    }

    /**
     * Run all parallel guards concurrently. Blocks until every guard has completed or until the
     * first one blocks — whichever comes first. The short-circuit on block is implemented by
     * using {@code anyOf} on a block-or-pass race.
     */
    public <T> GuardResult runParallel(List<Guard<T>> guards, Object ctx, String subject) {
        var parallelOnly = guards.stream().filter(g -> g.mode() == Guard.Mode.PARALLEL).toList();
        if (parallelOnly.isEmpty()) return GuardResult.pass();

        List<CompletableFuture<GuardResult>> futures = new java.util.ArrayList<>(parallelOnly.size());
        for (var g : parallelOnly) {
            futures.add(CompletableFuture.supplyAsync(
                    ContextScope.capturingSupplier(() -> safeCheck(g, ctx, subject)),
                    vexec));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        for (var f : futures) {
            GuardResult r = f.join();
            if (r.blocked()) return r;
        }
        return GuardResult.pass();
    }

    public <T> GuardResult runAfter(List<Guard<T>> guards, Object ctx, String subject) {
        for (var g : guards) {
            GuardResult r = safeCheck(g, ctx, subject);
            if (r.blocked()) return r;
        }
        return GuardResult.pass();
    }

    private <T> GuardResult safeCheck(Guard<T> g, Object ctx, String subject) {
        try {
            return g.check(ctx, subject);
        } catch (Throwable t) {
            return GuardResult.block("Guard '" + g.name() + "' threw: " + t.getMessage())
                    .withMetadata(g.name(),
                            g.phase() == Guard.Phase.INPUT ? GuardResult.Phase.INPUT : GuardResult.Phase.OUTPUT,
                            subject);
        }
    }
}
