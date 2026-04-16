package io.kite.anthropic;

import com.sun.net.httpserver.HttpServer;
import io.kite.Agent;
import io.kite.Event;
import io.kite.Kite;
import io.kite.Reply;
import io.kite.Status;
import io.kite.ToolChoice;
import io.kite.tracing.Tracing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicProviderIntegrationTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void runNonStreaming() {
        server.createContext("/v1/messages", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            lastRequestBody.set(new String(raw, StandardCharsets.UTF_8));
            String body = """
                    {
                      "id": "msg_1",
                      "model": "claude-3-5-sonnet-20241022",
                      "stop_reason": "end_turn",
                      "content": [{"type": "text", "text": "42"}],
                      "usage": {"input_tokens": 5, "output_tokens": 2}
                    }
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        var provider = new AnthropicProvider("test-key", baseUrl);
        var kite = Kite.builder().provider(provider).tracing(Tracing.off()).build();
        var agent = Agent.builder().model("claude-3-5-sonnet-20241022").instructions("Be terse.").build();

        Reply reply = kite.run(agent, "What's the answer?");
        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(reply.text()).isEqualTo("42");
        assertThat(lastRequestBody.get()).contains("\"system\":\"Be terse.\"");
        assertThat(lastRequestBody.get()).contains("\"stream\":false");
        kite.close();
    }

    @Test
    void streamingYieldsDeltasAndDoneEvent() {
        server.createContext("/v1/messages", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            lastRequestBody.set(new String(raw, StandardCharsets.UTF_8));
            StringBuilder sse = new StringBuilder();
            sse.append("event: message_start\n")
                    .append("data: {\"message\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":0}}}\n\n");
            sse.append("event: content_block_start\n")
                    .append("data: {\"index\":0,\"content_block\":{\"type\":\"text\"}}\n\n");
            sse.append("event: content_block_delta\n")
                    .append("data: {\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n");
            sse.append("event: content_block_delta\n")
                    .append("data: {\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\", world\"}}\n\n");
            sse.append("event: content_block_stop\n")
                    .append("data: {\"index\":0}\n\n");
            sse.append("event: message_delta\n")
                    .append("data: {\"usage\":{\"output_tokens\":5}}\n\n");
            sse.append("event: message_stop\n")
                    .append("data: {}\n\n");

            byte[] bytes = sse.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        var provider = new AnthropicProvider("test-key", baseUrl);
        var kite = Kite.builder().provider(provider).tracing(Tracing.off()).build();
        var agent = Agent.builder().model("claude-3-5-sonnet-20241022").build();

        List<Event> events = new ArrayList<>();
        kite.stream(agent, "Hi", events::add);

        long deltas = events.stream().filter(e -> e instanceof Event.Delta).count();
        assertThat(deltas).isEqualTo(2);
        Event.Done done = (Event.Done) events.get(events.size() - 1);
        assertThat(done.reply().text()).isEqualTo("Hello, world");
        assertThat(done.reply().usage().completionTokens()).isEqualTo(5);
        assertThat(lastRequestBody.get()).contains("\"stream\":true");
        kite.close();
    }

    @Test
    void toolChoiceFusesWithDisableParallelToolUseInWireFormat() {
        server.createContext("/v1/messages", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            lastRequestBody.set(new String(raw, StandardCharsets.UTF_8));
            String body = """
                    {
                      "id": "msg_1",
                      "model": "claude-3-5-sonnet-20241022",
                      "stop_reason": "end_turn",
                      "content": [{"type": "text", "text": "ok"}],
                      "usage": {"input_tokens": 1, "output_tokens": 1}
                    }
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        var provider = new AnthropicProvider("test-key", baseUrl);
        var kite = Kite.builder().provider(provider).tracing(Tracing.off()).build();
        var agent = Agent.builder().model("claude-3-5-sonnet-20241022")
                .toolChoice(ToolChoice.required())
                .parallelToolCalls(false)
                .build();

        Reply reply = kite.run(agent, "anything");
        assertThat(reply.status()).isEqualTo(Status.OK);
        String body = lastRequestBody.get();
        // Fusion produces {"type":"any","disable_parallel_tool_use":true} on a single object.
        assertThat(body).contains("\"tool_choice\":{");
        assertThat(body).contains("\"type\":\"any\"");
        assertThat(body).contains("\"disable_parallel_tool_use\":true");
        assertThat(body).doesNotContain("\"parallel_tool_calls\"");
        kite.close();
    }
}
