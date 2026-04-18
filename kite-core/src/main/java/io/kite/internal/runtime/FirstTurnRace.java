package io.kite.internal.runtime;

import io.kite.GuardResult;
import io.kite.model.ChatResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The tricky shared kernel of the first-turn guard race: wait for either a blocking guard or
 * the LLM future, and if the LLM future fails, defer to the guards before propagating the
 * error (a guard block is a more informative outcome than an opaque transport failure).
 *
 * <p>Everything else around the race — speculative tool dispatch, emitter gating, final
 * {@code completion().join()} on the guards — stays in {@link Runner}, which uses this
 * helper once and branches on the result.
 */
final class FirstTurnRace {

    private FirstTurnRace() {}

    sealed interface StepResult {
        record LlmReady(ChatResponse response) implements StepResult {}
        record EarlyBlock(GuardResult guardResult) implements StepResult {}
    }

    static StepResult awaitFirstStep(CompletableFuture<ChatResponse> llmFuture,
                                     ParallelGuardHandle guards) {
        Object winner;
        try {
            winner = CompletableFuture.anyOf(guards.firstBlock(), llmFuture).join();
        } catch (CompletionException ce) {
            GuardResult gr = guards.completion().join();
            if (gr.blocked()) return new StepResult.EarlyBlock(gr);
            Throwable cause = Throwables.unwrapCompletion(ce);
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
        if (winner instanceof GuardResult gr && gr.blocked()) {
            return new StepResult.EarlyBlock(gr);
        }
        return new StepResult.LlmReady((ChatResponse) winner);
    }
}
