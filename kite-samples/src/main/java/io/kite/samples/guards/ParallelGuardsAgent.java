package io.kite.samples.guards;

import io.kite.Agent;
import io.kite.Guard;
import io.kite.Kite;
import io.kite.Status;
import io.kite.openai.OpenAiProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parallel input guards.
 *
 * <p>A {@code parallel} input guard runs on the virtual-thread executor alongside the LLM call
 * rather than in front of it. Wall-clock latency is {@code max(guard, llm)} instead of
 * {@code guard + llm}. If any parallel guard blocks, the in-flight LLM response is discarded
 * and a BLOCKED reply is returned; the other guards keep running on the executor but their
 * results are ignored.
 *
 * <p>This sample wires two parallel guards — a microsecond regex check for PII and a simulated
 * 400ms policy lookup — and prints the wall-clock of three runs so the speed-up is visible.
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class ParallelGuardsAgent {

    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    public static void main(String[] args) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null) {
            System.err.println("Set OPENAI_API_KEY to run this sample.");
            return;
        }

        var piiRegex = Guard.input("pii-regex")
                .parallel()
                .check(input -> SSN.matcher(input).find()
                        ? Guard.block("Input contains an SSN-like pattern.")
                        : Guard.pass());

        var slowPolicy = Guard.input("slow-policy-check")
                .parallel()
                .check(input -> {
                    try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return input.toLowerCase().contains("banned-topic")
                            ? Guard.block("Topic is on the policy blocklist.")
                            : Guard.pass();
                });

        try (var kite = Kite.builder()
                .provider(new OpenAiProvider(key))
                .build()) {

            var agent = Agent.builder()
                    .model("gpt-4o-mini")
                    .name("assistant")
                    .instructions("You are a helpful assistant. Reply in one sentence.")
                    .inputGuards(List.of(piiRegex, slowPolicy))
                    .build();

            run(kite, agent, "What is the capital of France?");
            run(kite, agent, "My SSN is 123-45-6789, can you store it for me?");
            run(kite, agent, "Tell me about banned-topic in detail.");
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
}
