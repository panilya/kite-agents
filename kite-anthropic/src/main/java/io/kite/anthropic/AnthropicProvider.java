package io.kite.anthropic;

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
 * ModelProvider for Anthropic's Messages API ({@code /v1/messages}). Implements both
 * {@link #chat} (non-streaming) and {@link #chatStream} (named-event SSE) against the same
 * endpoint.
 */
public final class AnthropicProvider implements ModelProvider {

    private static final String DEFAULT_VERSION = "2023-06-01";

    private final String apiKey;
    private final String baseUrl;
    private final String version;
    private final HttpTransport http;
    private final boolean ownsHttp;
    private final JsonCodec json;
    private final Duration requestTimeout;

    public AnthropicProvider(String apiKey) {
        this(apiKey, "https://api.anthropic.com", DEFAULT_VERSION, new HttpTransport(), Duration.ofSeconds(120), true);
    }

    public AnthropicProvider(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, DEFAULT_VERSION, new HttpTransport(), Duration.ofSeconds(120), true);
    }

    public AnthropicProvider(String apiKey, String baseUrl, String version, HttpTransport http, Duration requestTimeout) {
        this(apiKey, baseUrl, version, http, requestTimeout, false);
    }

    private AnthropicProvider(String apiKey, String baseUrl, String version, HttpTransport http, Duration requestTimeout, boolean ownsHttp) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.version = version;
        this.http = http;
        this.ownsHttp = ownsHttp;
        this.json = JsonCodec.shared();
        this.requestTimeout = requestTimeout;
    }

    @Override
    public void close() {
        if (ownsHttp) http.close();
    }

    @Override
    public boolean supports(String modelName) {
        return modelName != null && modelName.startsWith("claude-");
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String body = AnthropicChatSerializer.serialize(request, false);
        HttpResponse<String> resp = http.postJson(
                URI.create(baseUrl + "/v1/messages"),
                headers(false),
                body,
                requestTimeout);
        if (resp.statusCode() / 100 != 2) {
            throw new AnthropicProviderException(resp.statusCode(), resp.body());
        }
        return AnthropicMessageParser.parse(json.readTree(resp.body()));
    }

    @Override
    public void chatStream(ChatRequest request, Consumer<ChatChunk> onChunk) {
        String body = AnthropicChatSerializer.serialize(request, true);
        HttpResponse<Stream<String>> resp = http.postJsonSse(
                URI.create(baseUrl + "/v1/messages"),
                headers(true),
                body,
                requestTimeout);
        if (resp.statusCode() / 100 != 2) {
            StringBuilder sb = new StringBuilder();
            try (Stream<String> s = resp.body()) {
                s.forEach(line -> sb.append(line).append('\n'));
            }
            throw new AnthropicProviderException(resp.statusCode(), sb.toString());
        }
        var parser = new SseParser();
        var dispatcher = new AnthropicStreamDispatcher(json, onChunk);
        try (Stream<String> lines = resp.body()) {
            lines.forEach(line -> parser.feedLine(line, dispatcher::onSseEvent));
            parser.feedLine("", dispatcher::onSseEvent);
        }
    }

    private Map<String, String> headers(boolean sse) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-api-key", apiKey);
        h.put("anthropic-version", version);
        h.put("Content-Type", "application/json");
        h.put("Accept", sse ? "text/event-stream" : "application/json");
        return h;
    }

    public static final class AnthropicProviderException extends RuntimeException {
        private final int statusCode;
        private final String body;

        public AnthropicProviderException(int statusCode, String body) {
            super("Anthropic returned HTTP " + statusCode + ": " + truncate(body));
            this.statusCode = statusCode;
            this.body = body;
        }

        public int statusCode() { return statusCode; }
        public String body() { return body; }

        private static String truncate(String s) {
            if (s == null) return "";
            return s.length() <= 400 ? s : s.substring(0, 400) + "…";
        }
    }
}
