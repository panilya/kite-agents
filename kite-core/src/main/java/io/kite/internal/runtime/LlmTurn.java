package io.kite.internal.runtime;

import io.kite.model.ChatRequest;
import io.kite.model.ChatResponse;
import io.kite.model.ModelProvider;
import io.kite.model.Usage;

/**
 * Strategy for running a single LLM turn. Two variants — {@link #CHAT} for non-streaming
 * (one call, one response) and {@link #STREAM} for streaming (chunks accumulated into a
 * synthetic {@link ChatResponse}) — collapse the only real difference between the
 * non-streaming and streaming run loops.
 *
 * <p>Provider is passed per invocation rather than held as state because a route transfer
 * can change the target model, and Runner re-resolves the provider every turn.
 */
@FunctionalInterface
interface LlmTurn {

    ChatResponse run(ModelProvider provider, ChatRequest request, String agentName, RunEmitter emitter);

    /** Wraps a plain {@link ModelProvider#chat(ChatRequest)} call. */
    LlmTurn CHAT = (provider, request, agentName, emitter) -> provider.chat(request);

    /**
     * Wraps a {@link ModelProvider#chatStream streaming} call, accumulating chunks into
     * a {@link ChatResponse} of the same shape the non-streaming path returns. The
     * {@code stopReason} is set to {@code "stop"} — streaming providers don't surface a
     * synthetic stop reason through chunks, and nothing downstream inspects it.
     */
    LlmTurn STREAM = (provider, request, agentName, emitter) -> {
        TurnAccumulator acc = new TurnAccumulator();
        provider.chatStream(request, chunk -> ChunkDispatcher.dispatch(chunk, agentName, acc, emitter));
        return new ChatResponse(
                acc.text.toString(),
                acc.finalToolCalls(),
                acc.usage == null ? Usage.ZERO : acc.usage,
                "stop",
                request.model(),
                null);
    };
}
