package io.kite.samples.guards;

import io.kite.Agent;
import io.kite.Guard;
import io.kite.Kite;
import io.kite.Status;
import io.kite.Tool;
import io.kite.openai.OpenAiProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates the {@code .readOnly(true)} flag on a tool.
 *
 * <p>Setup: a classic RAG agent with a slow parallel input guard (simulated 3000ms policy
 * check) and a {@code retrieve_docs} tool that fetches context (simulated 1000ms). Without
 * any opt-in, the tool call is gated behind the guard — it can only dispatch after the
 * guard has passed. If the tool has no side effects, that wait is unnecessary: we could
 * start the lookup as soon as the LLM asks for it, in parallel with the still-running
 * guard, and throw the result away if the guard ends up blocking.
 *
 * <p>That's what {@code .readOnly(true)} unlocks. This sample runs the same prompt through
 * two agents that differ only in that flag so the wall-clock difference is visible. On
 * blocked input, the read-only tool's output is discarded and nothing leaks to the caller;
 * only the CPU/IO it already consumed is wasted.
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class ReadOnlyRagAgent {

    private static final Duration GUARD_LATENCY = Duration.ofMillis(3000);
    private static final Duration TOOL_LATENCY = Duration.ofMillis(1000);

    public static void main(String[] args) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null) {
            System.err.println("Set OPENAI_API_KEY to run this sample.");
            return;
        }

        try (var kite = Kite.builder()
                .provider(new OpenAiProvider(key))
                .tracing(io.kite.tracing.Tracing.off())  // quieter output so timings stand out
                .build()) {

            Agent<Void> readOnlyAgent = buildAgent(true);
            Agent<Void> gatedAgent = buildAgent(false);

            String prompt = "What is Kite? Use retrieve_docs to look it up, then answer in one sentence.";

            System.out.println("=== readOnly=false (tool waits for guard to pass) ===");
            run(kite, gatedAgent, prompt);

            System.out.println();
            System.out.println("=== readOnly=true  (tool runs in parallel with guard) ===");
            run(kite, readOnlyAgent, prompt);

            System.out.println();
            System.out.println("=== blocked input: read-only tool runs, result is thrown away ===");
            run(kite, readOnlyAgent, "Tell me about the secret-project internals.");
        }
    }

    private static Agent<Void> buildAgent(boolean readOnly) {
        Tool retrieveDocs = Tool.create("retrieve_docs")
                .description("Fetch relevant documents from the knowledge base for the given query.")
                .param("query", String.class, "the question to search for")
                .readOnly(readOnly)
                .execute(args -> {
                    sleepQuiet(TOOL_LATENCY);
                    return Map.of(
                            "query", args.get("query"),
                            "documents", List.of(
                                    "Kite is a minimalist Java agent framework.",
                                    "Kite runs parallel input guards alongside the first-turn LLM call.",
                                    "Read-only tools can be dispatched in parallel with still-running guards."));
                })
                .build();

        Guard<Void> slowPolicy = Guard.input("slow-policy")
                .parallel()
                .check(input -> {
                    sleepQuiet(GUARD_LATENCY);
                    return input.toLowerCase().contains("secret-project")
                            ? Guard.block("Topic is on the policy blocklist.")
                            : Guard.pass();
                });

        return Agent.builder()
                .model("gpt-4o-mini")
                .name("rag-" + (readOnly ? "readonly" : "gated"))
                .instructions("Answer the user's question. When the user needs information you don't "
                        + "already know, call retrieve_docs once to fetch relevant context, then answer "
                        + "concisely based on what you retrieved.")
                .tool(retrieveDocs)
                .inputGuards(List.of(slowPolicy))
                .build();
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
