package io.kite.samples.guards;

import io.kite.Agent;
import io.kite.guards.Guard;
import io.kite.guards.GuardDecision;
import io.kite.guards.InputGuard;
import io.kite.Kite;
import io.kite.Status;
import io.kite.annotations.Tool;
import io.kite.annotations.ToolParam;
import io.kite.openai.OpenAiProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The same read-only-speculation feature as {@code ReadOnlyRagAgent}, but the tools live on
 * a plain POJO bean annotated with {@link Tool @Tool} instead of the programmatic
 * {@link io.kite.ToolBuilder}. This is the path most applications will use.
 *
 * <p>Set {@code readOnly = true} on the annotation and Kite will start the tool in parallel
 * with any still-running parallel input guards (throwing the result away on a block). Tools
 * without the flag stay gated behind guard resolution, as before.
 *
 * <p>The bean holds a tiny in-memory "knowledge base" so the pattern is self-contained.
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class AnnotatedReadOnlyAgent {

    private static final Duration GUARD_LATENCY = Duration.ofMillis(1500);

    public static final class KnowledgeBase {
        @Tool(name = "retrieve_docs", description = "Fetch relevant documents for a query", readOnly = true)
        public Map<String, Object> retrieveDocs(
                @ToolParam(description = "question to search for") String query) {
            sleepQuiet(Duration.ofMillis(400));
            return Map.of(
                    "query", query,
                    "documents", List.of(
                            "Kite is a minimalist Java agent framework.",
                            "Read-only tools run in parallel with still-in-flight guards."));
        }

        @Tool(name = "log_query", description = "Record a user-submitted question in the audit log")
        public String logQuery(
                @ToolParam(description = "question the user asked") String query) {
            // Simulated side effect — writes to an external audit log. Must NOT be readOnly.
            return "Logged: " + query;
        }
    }

    public static void main(String[] args) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null) {
            System.err.println("Set OPENAI_API_KEY to run this sample.");
            return;
        }

        try (var kite = Kite.builder()
                .provider(new OpenAiProvider(key))
                .tracing(io.kite.tracing.Tracing.off())
                .build()) {

            InputGuard<Void> slowPolicy = Guard.<Void>input("slow-policy")
                    .parallel()
                    .check(in -> { sleepQuiet(GUARD_LATENCY); return GuardDecision.allow(); });

            var agent = Agent.builder()
                    .model("gpt-4o-mini")
                    .name("annotated-rag")
                    .instructions("Answer the user's question. Use retrieve_docs to fetch context "
                            + "first, then answer concisely based on what you retrieved.")
                    .tools(new KnowledgeBase())
                    .inputGuards(List.of(slowPolicy))
                    .build();

            System.out.println("Tools discovered from annotated bean:");
            for (var t : agent.tools()) {
                System.out.println("  " + t.name() + "  readOnly=" + t.readOnly());
            }
            System.out.println();

            run(kite, agent, "What is Kite? Use retrieve_docs to look it up, then answer in one sentence.");
        }
    }

    private static void run(Kite kite, Agent<Void> agent, String prompt) {
        System.out.println("> " + prompt);
        Instant start = Instant.now();
        var reply = kite.run(agent, prompt);
        long ms = Duration.between(start, Instant.now()).toMillis();
        if (reply.status() == Status.BLOCKED) {
            System.out.println("  BLOCKED in " + ms + "ms: " + reply.blockReason());
        } else {
            System.out.println("  OK in " + ms + "ms: " + reply.text());
        }
    }

    private static void sleepQuiet(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
