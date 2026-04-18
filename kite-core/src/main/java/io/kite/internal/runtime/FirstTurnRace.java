package io.kite.internal.runtime;

import io.kite.guards.GuardOutcome;
import io.kite.model.ChatResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The tricky shared kernel of the first-turn guard race: wait for either a blocking guard or
 * the LLM future, and if the LLM future fails, defer to the guards before propagating the
 * error (a guard block is a more informative outcome than an opaque transport failure).
 */
final class FirstTurnRace {

    private FirstTurnRace() {}

    sealed interface StepResult {
        record LlmReady(ChatResponse response) implements StepResult {}
        record EarlyBlock(GuardOutcome outcome) implements StepResult {}
    }

    static StepResult awaitFirstStep(CompletableFuture<ChatResponse> llmFuture,
                                     ParallelGuardHandle guards) {
        Object winner;
        try {
            winner = CompletableFuture.anyOf(guards.firstBlock(), llmFuture).join();
        } catch (CompletionException ce) {
            // LLM failed. Prefer a guard block as a more informative outcome — race
            // firstBlock against allOutcomes so a single block short-circuits without
            // waiting for every remaining guard.
            GuardOutcome blocked = raceForBlock(guards);
            if (blocked != null) return new StepResult.EarlyBlock(blocked);
            Throwable cause = Throwables.unwrapCompletion(ce);
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
        if (winner instanceof GuardOutcome o && o.blocked()) {
            return new StepResult.EarlyBlock(o);
        }
        return new StepResult.LlmReady((ChatResponse) winner);
    }

    private static GuardOutcome raceForBlock(ParallelGuardHandle guards) {
        Object winner;
        try {
            winner = CompletableFuture.anyOf(guards.firstBlock(), guards.allOutcomes()).join();
        } catch (CompletionException ignored) {
            return null;
        }
        if (winner instanceof GuardOutcome go && go.blocked()) return go;
        if (winner instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof GuardOutcome g && g.blocked()) return g;
            }
        }
        return null;
    }
}
