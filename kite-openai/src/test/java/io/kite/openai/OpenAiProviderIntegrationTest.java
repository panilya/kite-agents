package io.kite.openai;

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

/**
 * End-to-end test that spins up a real HTTP server mimicking {@code /v1/responses}, and drives
 * both {@code run} and {@code stream} through the full {@link OpenAiProvider} → {@link Kite}
 * → {@link io.kite.internal.runtime.Runner} stack. No external HTTP dependency — uses the JDK's
 * built-in {@code com.sun.net.httpserver.HttpServer}.
 */
class OpenAiProviderIntegrationTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void runNonStreamingRoundTripsThroughProvider() {
        server.createContext("/v1/responses", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            lastRequestBody.set(new String(raw, StandardCharsets.UTF_8));
            String body = """
                    {
                      "id": "resp_1",
                      "model": "gpt-4o",
                      "status": "completed",
                      "output": [
                        {"type": "message", "role": "assistant", "content": [
                          {"type": "output_text", "text": "42"}
                        ]}
                      ],
                      "usage": {"input_tokens": 5, "output_tokens": 2, "total_tokens": 7}
                    }
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        var provider = new OpenAiProvider("test-key", baseUrl);
        var kite = Kite.builder().provider(provider).tracing(Tracing.off()).build();
        var agent = Agent.builder().model("gpt-4o").instructions("You are a calculator.").build();

        Reply reply = kite.run(agent, "What is the answer?");
        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(reply.text()).isEqualTo("42");
        assertThat(reply.usage().totalTokens()).isEqualTo(7);
        assertThat(lastRequestBody.get()).contains("\"stream\":false");
        kite.close();
    }

    @Test
    void streamSseRoundTripsThroughProvider() {
        server.createContext("/v1/responses", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            lastRequestBody.set(new String(raw, StandardCharsets.UTF_8));

            StringBuilder sse = new StringBuilder();
            sse.append("event: response.created\n")
                    .append("data: {\"response\":{\"id\":\"resp_1\"}}\n\n");
            sse.append("event: response.output_text.delta\n")
                    .append("data: {\"delta\":\"Hello\"}\n\n");
            sse.append("event: response.output_text.delta\n")
                    .append("data: {\"delta\":\", \"}\n\n");
            sse.append("event: response.output_text.delta\n")
                    .append("data: {\"delta\":\"world!\"}\n\n");
            sse.append("event: response.completed\n")
                    .append("data: {\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":3,\"output_tokens\":4,\"total_tokens\":7}}}\n\n");

            byte[] bytes = sse.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        var provider = new OpenAiProvider("test-key", baseUrl);
        var kite = Kite.builder().provider(provider).tracing(Tracing.off()).build();
        var agent = Agent.builder().model("gpt-4o").build();

        List<Event> events = new ArrayList<>();
        kite.stream(agent, "Hi", events::add);

        long deltaCount = events.stream().filter(e -> e instanceof Event.Delta).count();
        assertThat(deltaCount).isEqualTo(3);
        Event.Done done = (Event.Done) events.get(events.size() - 1);
        assertThat(done.reply().text()).isEqualTo("Hello, world!");
        assertThat(done.reply().usage().totalTokens()).isEqualTo(7);
        assertThat(lastRequestBody.get()).contains("\"stream\":true");
        kite.close();
    }

    @Test
    void toolChoiceAndParallelToolCallsEmittedInWireFormat() {
        server.createContext("/v1/responses", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            lastRequestBody.set(new String(raw, StandardCharsets.UTF_8));
            String body = """
                    {
                      "id": "resp_1",
                      "model": "gpt-4o",
                      "status": "completed",
                      "output": [
                        {"type": "message", "role": "assistant", "content": [
                          {"type": "output_text", "text": "ok"}
                        ]}
                      ],
                      "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}
                    }
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        var provider = new OpenAiProvider("test-key", baseUrl);
        var kite = Kite.builder().provider(provider).tracing(Tracing.off()).build();
        var agent = Agent.builder().model("gpt-4o")
                .toolChoice(ToolChoice.required())
                .parallelToolCalls(false)
                .build();

        Reply reply = kite.run(agent, "anything");
        assertThat(reply.status()).isEqualTo(Status.OK);
        assertThat(lastRequestBody.get()).contains("\"tool_choice\":\"required\"");
        assertThat(lastRequestBody.get()).contains("\"parallel_tool_calls\":false");
        kite.close();
    }
}
