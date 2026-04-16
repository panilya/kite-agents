package io.kite.samples;

import io.kite.Agent;
import io.kite.Kite;
import io.kite.openai.OpenAiProvider;

/**
 * The smallest possible Kite agent: no tools, no typed context, one call.
 *
 * <p>Run with {@code OPENAI_API_KEY} set. Set {@code KITE_TRACING=off} to
 * silence the default console trace on stderr.
 */
public final class HelloAgent {

    public static void main(String[] args) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null) {
            System.err.println("Set OPENAI_API_KEY to run this sample.");
            return;
        }

        try (var kite = Kite.builder()
                .provider(new OpenAiProvider(key))
                .build()) {

            var agent = Agent.builder()
                    .model("gpt-4o-mini")
                    .name("greeter")
                    .instructions("You are a friendly greeter. Reply in one sentence.")
                    .build();

            var reply = kite.run(agent, "Say hello to someone learning Kite.");
            System.out.println(reply.text());
        }
    }
}
