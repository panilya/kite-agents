package io.kite.internal.runtime;

import io.kite.model.ChatChunk;
import io.kite.model.ChatRequest;
import io.kite.model.ChatResponse;
import io.kite.model.Message;
import io.kite.model.ModelProvider;
import io.kite.model.Usage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Model provider for unit tests. Supports two scripts: one for {@code chat(...)} and one for
 * {@code chatStream(...)}. Each script is consumed in turn order — the first call returns the
 * first scripted response, and so on. Incoming {@link ChatRequest}s are recorded for assertions.
 *
 * <p>Per-response latency is configurable via {@link Builder#withLatency(Duration)}, which
 * applies to every subsequent scripted response until changed again. Tests that need timing
 * guarantees (e.g. the parallel-guard race) use this to simulate a slow LLM.
 *
 * <p>Public so that tests in any module can use it.
 */
public final class MockModelProvider implements ModelProvider {

    private final Predicate<String> supports;
    private final Iterator<Scripted<ChatResponse>> chatScript;
    private final Iterator<Scripted<List<ChatChunk>>> streamScript;
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
        Scripted<ChatResponse> s = chatScript.next();
        sleep(s.latency);
        return s.value;
    }

    @Override
    public void chatStream(ChatRequest request, Consumer<ChatChunk> onChunk) {
        recorded.add(request);
        if (!streamScript.hasNext()) {
            throw new IllegalStateException("MockModelProvider.chatStream() called more times than scripted");
        }
        Scripted<List<ChatChunk>> s = streamScript.next();
        sleep(s.latency);
        for (var c : s.value) onChunk.accept(c);
    }

    private static void sleep(Duration d) {
        if (d == null || d.isZero() || d.isNegative()) return;
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("MockModelProvider interrupted", e);
        }
    }

    private record Scripted<T>(T value, Duration latency) {}

    public static final class Builder {
        private Predicate<String> supports = m -> true;
        private final List<Scripted<ChatResponse>> chatResponses = new ArrayList<>();
        private final List<Scripted<List<ChatChunk>>> streamResponses = new ArrayList<>();
        private Duration nextLatency = Duration.ZERO;

        public Builder supports(Predicate<String> predicate) {
            this.supports = predicate;
            return this;
        }

        /**
         * Sleep for {@code d} before returning the next (and every subsequent, until changed)
         * scripted response. Applies to both chat and stream scripts independently.
         */
        public Builder withLatency(Duration d) {
            this.nextLatency = d == null ? Duration.ZERO : d;
            return this;
        }

        public Builder respond(ChatResponse response) {
            chatResponses.add(new Scripted<>(response, nextLatency));
            return this;
        }

        public Builder respondText(String text) {
            return respond(new ChatResponse(text, List.of(), Usage.ZERO, "stop", "mock", "resp-" + chatResponses.size()));
        }

        public Builder respondToolCall(String toolCallId, String name, String argsJson) {
            return respond(new ChatResponse(
                    "",
                    List.of(new Message.ToolCallRef(toolCallId, name, argsJson)),
                    Usage.ZERO, "tool_calls", "mock", "resp-" + chatResponses.size()));
        }

        public Builder respondToolCalls(Message.ToolCallRef... calls) {
            return respond(new ChatResponse(
                    "",
                    List.of(calls),
                    Usage.ZERO, "tool_calls", "mock", "resp-" + chatResponses.size()));
        }

        public Builder stream(List<ChatChunk> chunks) {
            streamResponses.add(new Scripted<>(List.copyOf(chunks), nextLatency));
            return this;
        }

        public Builder streamText(String... tokens) {
            List<ChatChunk> chunks = new ArrayList<>();
            for (String t : tokens) chunks.add(new ChatChunk.TextDelta(t));
            chunks.add(new ChatChunk.Done(Usage.ZERO, "stop"));
            return stream(chunks);
        }

        public MockModelProvider build() {
            return new MockModelProvider(this);
        }
    }
}
