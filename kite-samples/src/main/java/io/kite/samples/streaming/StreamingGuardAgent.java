package io.kite.samples.streaming;

import io.kite.Agent;
import io.kite.Event;
import io.kite.guards.Guard;
import io.kite.guards.GuardDecision;
import io.kite.guards.InputGuard;
import io.kite.Kite;
import io.kite.StreamBehavior;
import io.kite.openai.OpenAiProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Streaming with a slow parallel input guard — shows the two {@link StreamBehavior} modes
 * side by side.
 *
 * <ul>
 *   <li><b>BUFFER</b> (default): deltas are held in memory until every parallel guard
 *       resolves, then flushed downstream all at once. On block, the held deltas are
 *       discarded — downstream never sees them. Pays the guard wait before the first byte.</li>
 *   <li><b>PASSTHROUGH</b>: deltas stream to downstream live. If a guard blocks partway
 *       through, already-emitted text stays visible but further deltas stop. Lowest
 *       time-to-first-byte.</li>
 * </ul>
 *
 * <p>Each run below prints every event with a relative timestamp so the delivery
 * pattern is visible on the console. Tool calls still happen only after guards pass, so
 * {@code ToolCall}/{@code ToolResult}/{@code Transfer} never leak on a blocked run in
 * either mode — only {@code Delta} text can leak under PASSTHROUGH.
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class StreamingGuardAgent {

    private static final Duration GUARD_LATENCY = Duration.ofMillis(1500);

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

            System.out.println("=== BUFFER: no deltas until guard passes ===");
            run(kite, buildAgent(StreamBehavior.BUFFER, "buffered"),
                    "Tell me a one-sentence fact about kites.");

            System.out.println();
            System.out.println("=== PASSTHROUGH: deltas stream live ===");
            run(kite, buildAgent(StreamBehavior.PASSTHROUGH, "live"),
                    "Tell me a one-sentence fact about kites.");
        }
    }

    private static Agent<Void> buildAgent(StreamBehavior behavior, String label) {
        InputGuard<Void> slowPolicy = Guard.<Void>input("slow-policy")
                .parallel()
                .streamBehavior(behavior)
                .check(in -> { sleepQuiet(GUARD_LATENCY); return GuardDecision.allow(); });

        return Agent.builder()
                .model("gpt-4o-mini")
                .name("storyteller-" + label)
                .instructions("You are a storyteller. Keep responses under two sentences.")
                .inputGuards(List.of(slowPolicy))
                .build();
    }

    private static void run(Kite kite, Agent<Void> agent, String prompt) {
        System.out.println("> " + prompt);
        Instant start = Instant.now();
        kite.stream(agent, prompt, event -> {
            long ms = Duration.between(start, Instant.now()).toMillis();
            switch (event) {
                case Event.Delta d -> System.out.printf("[+%4dms delta]  %s%n", ms, d.text().replace("\n", " "));
                case Event.GuardCheck gc -> {
                    if (gc.outcome().blocked()) {
                        System.out.printf("[+%4dms BLOCKED] guard=%s %s%n",
                                ms, gc.outcome().name(), gc.outcome().message());
                    }
                }
                case Event.Done done -> System.out.printf("[+%4dms done]   status=%s%n", ms, done.reply().status());
                default -> { /* ignored for this demo */ }
            }
        });
    }

    private static void sleepQuiet(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
