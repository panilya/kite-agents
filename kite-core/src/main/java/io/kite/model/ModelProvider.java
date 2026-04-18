package io.kite.model;

import java.util.function.Consumer;

/**
 * The only extension point Kite requires for reaching an LLM. Implementations live in separate
 * modules (kite-openai, kite-anthropic, etc.) and are registered explicitly on KiteBuilder — no
 * classpath scanning. The first provider whose {@link #supports(String)} returns true wins.
 *
 * <p>Both {@link #chat} and {@link #chatStream} are first-class. Kite dispatches to whichever
 * matches the caller's entry point. Neither is implemented in terms of the other.
 */
public interface ModelProvider extends AutoCloseable {

    /** True if this provider recognizes the given model name. */
    boolean supports(String modelName);

    /** Non-streaming call. Returns a complete response in one shot. Blocks until done. */
    ChatResponse chat(ChatRequest request);

    /**
     * Streaming call. Invokes {@code onChunk} synchronously as each chunk is parsed.
     * The final chunk is always {@link ChatChunk.Done} (on success) or {@link ChatChunk.Error}
     * (on failure). Blocks until the stream ends.
     */
    void chatStream(ChatRequest request, Consumer<ChatChunk> onChunk);

    /** Release resources held by this provider (HTTP clients, thread pools, etc.). Default no-op. */
    @Override
    default void close() {}
}
