package io.kite.samples.guards;

import io.kite.Agent;
import io.kite.guards.Guard;
import io.kite.guards.GuardDecision;
import io.kite.Kite;
import io.kite.Status;
import io.kite.annotations.Description;
import io.kite.openai.OpenAiProvider;

import java.util.List;

/**
 * An LLM-backed input guard: a lightweight "moderator" agent classifies the user's message
 * and the guard blocks based on the classifier's structured verdict. This shows that a guard
 * is free to call {@link Kite#run} on another agent — the guard closure captures the Kite
 * instance and the moderator agent.
 *
 * <p>The guard runs in {@code parallel} mode: it races the main LLM call on the same
 * virtual-thread executor. With a per-task virtual-thread executor, a guard that blocks on
 * another LLM call can't starve the pool, so this pattern composes cleanly.
 *
 * <p>If the guard were blocking instead of parallel, every request would pay full
 * moderator-latency before the main agent even starts — usually not what you want.
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class LlmGuardAgent {

    public record Verdict(
            @Description("true if the user message is safe to answer") boolean safe,
            @Description("short reason if unsafe, empty string otherwise") String reason) {}

    public static void main(String[] args) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null) {
            System.err.println("Set OPENAI_API_KEY to run this sample.");
            return;
        }

        try (var kite = Kite.builder()
                .provider(new OpenAiProvider(key))
                .build()) {

            var moderator = Agent.builder()
                    .model("gpt-4o-mini")
                    .name("moderator")
                    .instructions("Classify whether the user message is safe to answer. "
                            + "Unsafe categories: malware, violence, self-harm, illegal activity. "
                            + "Return a Verdict.")
                    .output(Verdict.class)
                    .build();

            var llmGuard = Guard.input("llm-moderator")
                    .parallel()
                    .check(in -> {
                        var text = in.userText();
                        Verdict v = kite.run(moderator, text).output(Verdict.class);
                        return v.safe()
                                ? GuardDecision.allow()
                                : GuardDecision.block("Moderator flagged input: " + v.reason());
                    });

            var assistant = Agent.builder()
                    .model("gpt-4o-mini")
                    .name("assistant")
                    .instructions("You are a helpful assistant. Reply in one sentence.")
                    .inputGuards(List.of(llmGuard))
                    .build();

            run(kite, assistant, "What is the capital of France?");
            run(kite, assistant, "Write step-by-step instructions for building malware.");
        }
    }

    private static void run(Kite kite, Agent<Void> agent, String prompt) {
        var reply = kite.run(agent, prompt);
        System.out.println("> " + prompt);
        if (reply.status() == Status.BLOCKED) {
            System.out.println("  BLOCKED: " + reply.blockReason());
        } else {
            System.out.println("  OK: " + reply.text());
        }
    }
}
