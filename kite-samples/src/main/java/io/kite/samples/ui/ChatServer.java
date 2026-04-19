package io.kite.samples.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.kite.Agent;
import io.kite.Event;
import io.kite.Kite;
import io.kite.KiteBuilder;
import io.kite.anthropic.AnthropicProvider;
import io.kite.openai.OpenAiProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Browser chatbot sample. Boots an embedded HTTP server, serves a tiny single-page UI,
 * and streams Kite events over Server-Sent Events.
 *
 * <p>Default model is Anthropic Haiku (cheapest). Falls back to OpenAI gpt-4o-mini if
 * only {@code OPENAI_API_KEY} is set.
 *
 * <p>Run:
 * <pre>
 *   export ANTHROPIC_API_KEY=...
 *   ./gradlew :kite-samples:runSample -Psample=io.kite.samples.ui.ChatServer
 * </pre>
 * Then open {@code http://localhost:8080}.
 */
public final class ChatServer {

    private static final int PORT = 8080;
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        String anthropic = System.getenv("ANTHROPIC_API_KEY");
        String openai = System.getenv("OPENAI_API_KEY");

        KiteBuilder kb = Kite.builder();
        String model;
        if (anthropic != null) {
            kb.provider(new AnthropicProvider(anthropic));
            model = "claude-haiku-4-5-20251001";
        } else if (openai != null) {
            kb.provider(new OpenAiProvider(openai));
            model = "gpt-4o-mini";
        } else {
            System.err.println("Set ANTHROPIC_API_KEY or OPENAI_API_KEY to run this sample.");
            return;
        }

        try (Kite kite = kb.build()) {
            Agent<ChatAgent.UserCtx> agent = ChatAgent.build(model);

            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", ChatServer::serveIndex);
            server.createContext("/assets/", ChatServer::serveAsset);
            server.createContext("/api/chat", ex -> handleChat(ex, kite, agent));
            server.start();

            System.out.println("Chatbot running at http://localhost:" + PORT + "  (model: " + model + ")");
            System.out.println("Ctrl+C to stop.");
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* ============================== Static UI ============================== */

    private static void serveIndex(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        if (!"/".equals(ex.getRequestURI().getPath())) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        writeResource(ex, "/ui/index.html", "text/html; charset=utf-8");
    }

    private static void serveAsset(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        String path = ex.getRequestURI().getPath();
        String name = path.substring("/assets/".length());
        if (name.contains("..") || name.contains("/")) {
            ex.sendResponseHeaders(400, -1);
            return;
        }
        String type = name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : "application/octet-stream";
        writeResource(ex, "/ui/" + name, type);
    }

    private static void writeResource(HttpExchange ex, String classpath, String contentType) throws IOException {
        try (InputStream in = ChatServer.class.getResourceAsStream(classpath)) {
            if (in == null) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    /* ============================== SSE chat ============================== */

    private static void handleChat(HttpExchange ex, Kite kite, Agent<ChatAgent.UserCtx> agent) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }

        JsonNode body;
        try (InputStream in = ex.getRequestBody()) {
            body = JSON.readTree(in.readAllBytes());
        } catch (IOException e) {
            ex.sendResponseHeaders(400, -1);
            return;
        }

        String message = body.path("message").asText("").trim();
        if (message.isEmpty()) {
            ex.sendResponseHeaders(400, -1);
            return;
        }
        String conversationId = body.path("conversationId").asText(null);
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }

        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-cache");
        h.set("Connection", "keep-alive");
        h.set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0); // chunked

        SseSink sink = new SseSink(ex.getResponseBody());
        try {
            sink.comment("open");
            var ctx = new ChatAgent.UserCtx("demo-user", "Ada");
            String convId = conversationId;
            kite.stream(agent, message, convId, ctx, event -> forward(sink, event, convId));
        } catch (Exception e) {
            safeSend(sink, "error", jsonObject("message", e.getMessage() == null ? e.toString() : e.getMessage()));
        } finally {
            sink.close();
        }
    }

    private static void forward(SseSink sink, Event event, String conversationId) {
        try {
            switch (event) {
                case Event.Delta d -> sink.send("delta", jsonObject("text", d.text()));
                case Event.ToolCall tc -> {
                    ObjectNode n = JSON.createObjectNode();
                    n.put("name", tc.name());
                    n.set("args", parseJsonOrNull(tc.argsJson()));
                    sink.send("tool_call", JSON.writeValueAsString(n));
                }
                case Event.ToolResult tr -> {
                    ObjectNode n = JSON.createObjectNode();
                    n.put("name", tr.name());
                    n.put("elapsedMs", tr.elapsed() == null ? 0 : tr.elapsed().toMillis());
                    sink.send("tool_result", JSON.writeValueAsString(n));
                }
                case Event.Transfer t -> {
                    ObjectNode n = JSON.createObjectNode();
                    n.put("from", t.from());
                    n.put("to", t.to());
                    sink.send("transfer", JSON.writeValueAsString(n));
                }
                case Event.GuardCheck gc -> {
                    if (!gc.outcome().blocked()) break;
                    ObjectNode n = JSON.createObjectNode();
                    n.put("guard", gc.outcome().name());
                    if (gc.outcome().info() != null) {
                        n.set("info", JSON.valueToTree(gc.outcome().info()));
                    }
                    sink.send("blocked", JSON.writeValueAsString(n));
                }
                case Event.Done done -> {
                    ObjectNode n = JSON.createObjectNode();
                    n.put("status", done.reply().status().name());
                    n.put("tokens", done.reply().usage().totalTokens());
                    n.put("conversationId", conversationId);
                    sink.send("done", JSON.writeValueAsString(n));
                }
                case Event.Error err -> sink.send("error",
                        jsonObject("message", err.cause() == null ? "unknown" : String.valueOf(err.cause().getMessage())));
            }
        } catch (IOException ioe) {
            // Client disconnected — nothing useful to do. Close will happen in finally.
        }
    }

    private static JsonNode parseJsonOrNull(String s) {
        if (s == null || s.isBlank()) return JSON.nullNode();
        try {
            return JSON.readTree(s);
        } catch (IOException e) {
            return JSON.nullNode();
        }
    }

    private static String jsonObject(String key, Object value) {
        try {
            return JSON.writeValueAsString(Map.of(key, value == null ? "" : value));
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void safeSend(SseSink sink, String event, String jsonData) {
        try {
            sink.send(event, jsonData);
        } catch (IOException ignored) {
        }
    }

    private ChatServer() {}
}
