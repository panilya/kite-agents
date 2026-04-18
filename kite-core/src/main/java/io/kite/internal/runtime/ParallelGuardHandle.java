package io.kite.internal.runtime;

import io.kite.guards.GuardOutcome;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Live handle over a batch of {@code PARALLEL} input guards that have been started on the
 * virtual-thread executor. Lets {@link Runner} race the guards against the first-turn LLM call
 * and decide whether to commit or discard based on which finishes first.
 *
 * <p>{@link #firstBlock} completes only if some guard produces a block. {@link #allOutcomes}
 * completes once every guard has returned, carrying the full list of outcomes in completion
 * order. {@link #anyBuffer} drives streaming mode (BUFFER vs PASSTHROUGH).
 */
public record ParallelGuardHandle(
        CompletableFuture<GuardOutcome> firstBlock,
        CompletableFuture<List<GuardOutcome>> allOutcomes,
        boolean anyBuffer,
        List<CompletableFuture<GuardOutcome>> inflight) {

    public static ParallelGuardHandle empty() {
        var never = new CompletableFuture<GuardOutcome>();
        var pass = CompletableFuture.completedFuture(List.<GuardOutcome>of());
        return new ParallelGuardHandle(never, pass, false, List.of());
    }

    /**
     * Mark every still-running guard future as cancelled. The guard's underlying supplier
     * (running on a virtual thread) may continue to completion — {@link CompletableFuture#cancel}
     * does not interrupt the producer — but the downstream observer callback does not fire
     * once its stage is cancelled, so no further {@code TraceEvent.GuardCheck} events leak
     * from a run that has already returned.
     */
    public void cancelAll() {
        for (var f : inflight) f.cancel(true);
    }
}
