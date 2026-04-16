package io.kite.openai;

import io.kite.internal.http.HttpTransport;
import io.kite.internal.json.JsonCodec;
import io.kite.internal.sse.SseParser;
import io.kite.model.ChatChunk;
import io.kite.model.ChatRequest;
import io.kite.model.ChatResponse;
import io.kite.model.ModelProvider;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * ModelProvider for OpenAI's Responses API ({@code /v1/responses}). Implements both
 * {@link #chat} and {@link #chatStream} against the same endpoint — the former with
 * {@code stream: false}, the latter with {@code stream: true}.
 *
 * <p>Does not support OpenAI-compatible backends like Ollama or vLLM — those implement Chat
 * Completions, not Responses. A future {@code kite-openai-compat} module will cover them.
 */
public final class OpenAiProvider implements ModelProvider {

    private final String apiKey;
    private final String baseUrl;
    private final HttpTransport http;
    private final JsonCodec json;
    private final Duration requestTimeout;

    public OpenAiProvider(String apiKey) {
        this(apiKey, "https://api.openai.com/v1", new HttpTransport(), Duration.ofSeconds(120));
    }

    public OpenAiProvider(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, new HttpTransport(), Duration.ofSeconds(120));
    }

    public OpenAiProvider(String apiKey, String baseUrl, HttpTransport http, Duration requestTimeout) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
        this.json = JsonCodec.shared();
        this.requestTimeout = requestTimeout;
    }

    @Override
    public boolean supports(String modelName) {
        if (modelName == null) return false;
        return modelName.startsWith("gpt-")
                || modelName.startsWith("o1")
                || modelName.startsWith("o3")
                || modelName.startsWith("o4")
                || modelName.startsWith("chatgpt-");
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String body = OpenAiResponsesSerializer.serialize(request, false);
        HttpResponse<String> resp = http.postJson(
                URI.create(baseUrl + "/responses"),
                headers(false),
                body,
                requestTimeout);
        if (resp.statusCode() / 100 != 2) {
            throw new ProviderException("OpenAI", resp.statusCode(), resp.body());
        }
        return OpenAiResponsesParser.parse(json.readTree(resp.body()));
    }

    @Override
    public void chatStream(ChatRequest request, Consumer<ChatChunk> onChunk) {
        String body = OpenAiResponsesSerializer.serialize(request, true);
        HttpResponse<Stream<String>> resp = http.postJsonSse(
                URI.create(baseUrl + "/responses"),
                headers(true),
                body,
                requestTimeout);
        if (resp.statusCode() / 100 != 2) {
            // The body is a Stream<String>; join it for the error message.
            StringBuilder sb = new StringBuilder();
            try (Stream<String> s = resp.body()) {
                s.forEach(line -> sb.append(line).append('\n'));
            }
            throw new ProviderException("OpenAI", resp.statusCode(), sb.toString());
        }
        var parser = new SseParser();
        var dispatcher = new OpenAiResponsesStreamDispatcher(json, onChunk);
        try (Stream<String> lines = resp.body()) {
            lines.forEach(line -> parser.feedLine(line, dispatcher::onSseEvent));
            // Flush any trailing event (blank-line terminator may be missing).
            parser.feedLine("", dispatcher::onSseEvent);
        }
    }

    private Map<String, String> headers(boolean sse) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + apiKey);
        h.put("Content-Type", "application/json");
        h.put("Accept", sse ? "text/event-stream" : "application/json");
        return h;
    }
}
