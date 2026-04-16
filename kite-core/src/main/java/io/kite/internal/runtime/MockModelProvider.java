package io.kite.internal.runtime;

import io.kite.model.ChatChunk;
import io.kite.model.ChatRequest;
import io.kite.model.ChatResponse;
import io.kite.model.Message;
import io.kite.model.ModelProvider;
import io.kite.model.Usage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Model provider for unit tests. Supports two scripts: one for {@code chat(...)} and one for
 * {@code chatStream(...)}. Each script is consumed in turn order — the first call returns the
 * first scripted response, and so on. Incoming {@link ChatRequest}s are recorded for assertions.
 *
 * <p>Public so that tests in any module can use it.
 */
public final class MockModelProvider implements ModelProvider {

    private final Predicate<String> supports;
    private final Iterator<ChatResponse> chatScript;
    private final Iterator<List<ChatChunk>> streamScript;
    private final List<ChatRequest> recorded = new ArrayList<>();

    private MockModelProvider(Builder b) {
        this.supports = b.supports;
        this.chatScript = b.chatResponses.iterator();
        this.streamScript = b.streamResponses.iterator();
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ChatRequest> recorded() {
        return List.copyOf(recorded);
    }

    public ChatRequest lastRequest() {
        return recorded.isEmpty() ? null : recorded.get(recorded.size() - 1);
    }

    @Override
    public boolean supports(String modelName) {
        return supports.test(modelName);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        recorded.add(request);
        if (!chatScript.hasNext()) {
            throw new IllegalStateException("MockModelProvider.chat() called more times than scripted");
        }
        return chatScript.next();
    }

    @Override
    public void chatStream(ChatRequest request, Consumer<ChatChunk> onChunk) {
        recorded.add(request);
        if (!streamScript.hasNext()) {
            throw new IllegalStateException("MockModelProvider.chatStream() called more times than scripted");
        }
        List<ChatChunk> chunks = streamScript.next();
        for (var c : chunks) onChunk.accept(c);
    }

    public static final class Builder {
        private Predicate<String> supports = m -> true;
        private final List<ChatResponse> chatResponses = new ArrayList<>();
        private final List<List<ChatChunk>> streamResponses = new ArrayList<>();

        public Builder supports(Predicate<String> predicate) {
            this.supports = predicate;
            return this;
        }

        public Builder respond(ChatResponse response) {
            chatResponses.add(response);
            return this;
        }

        public Builder respondText(String text) {
            chatResponses.add(new ChatResponse(text, List.of(), Usage.ZERO, "stop", "mock", "resp-" + chatResponses.size()));
            return this;
        }

        public Builder respondToolCall(String toolCallId, String name, String argsJson) {
            chatResponses.add(new ChatResponse(
                    "",
                    List.of(new Message.ToolCallRef(toolCallId, name, argsJson)),
                    Usage.ZERO, "tool_calls", "mock", "resp-" + chatResponses.size()));
            return this;
        }

        public Builder stream(List<ChatChunk> chunks) {
            streamResponses.add(List.copyOf(chunks));
            return this;
        }

        public Builder streamText(String... tokens) {
            List<ChatChunk> chunks = new ArrayList<>();
            for (String t : tokens) chunks.add(new ChatChunk.TextDelta(t));
            chunks.add(new ChatChunk.Done(Usage.ZERO, "stop"));
            streamResponses.add(chunks);
            return this;
        }

        public MockModelProvider build() {
            return new MockModelProvider(this);
        }
    }
}
