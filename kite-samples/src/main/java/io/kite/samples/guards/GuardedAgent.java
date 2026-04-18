package io.kite.samples.guards;

import io.kite.Agent;
import io.kite.guards.Guard;
import io.kite.guards.GuardDecision;
import io.kite.Kite;
import io.kite.Status;
import io.kite.openai.OpenAiProvider;

import java.util.List;

/**
 * Input and output guards.
 *
 * <p>The input guard runs in blocking mode — if it blocks, the LLM is never
 * called and zero tokens are consumed. The output guard runs after the LLM
 * replies and can veto the response before it reaches the caller. Guards
 * never throw; a blocked guard produces a {@code Reply} with
 * {@code status == BLOCKED}.
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class GuardedAgent {

    public static void main(String[] args) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null) {
            System.err.println("Set OPENAI_API_KEY to run this sample.");
            return;
        }

        var noHacking = Guard.input("no-hacking")
                .blocking()
                .check(in -> {
                    var text = in.userText();
                    return text.toLowerCase().contains("hack")
                            ? GuardDecision.block("I can't help with hacking.")
                            : GuardDecision.allow();
                });

        var noEmptyReply = Guard.output("no-empty-reply")
                .check(in -> (in.generatedResponse() == null || in.generatedResponse().isBlank())
                        ? GuardDecision.block("Model returned an empty response.")
                        : GuardDecision.allow());

        try (var kite = Kite.builder()
                .provider(new OpenAiProvider(key))
                .build()) {

            var agent = Agent.builder()
                    .model("gpt-4o-mini")
                    .name("assistant")
                    .instructions("You are a helpful assistant. Reply in one sentence.")
                    .inputGuards(List.of(noHacking))
                    .outputGuards(List.of(noEmptyReply))
                    .build();

            run(kite, agent, "What is the capital of France?");
            run(kite, agent, "How do I hack a wifi network?");
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
