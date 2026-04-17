package io.kite.samples.delegation;

import io.kite.Agent;
import io.kite.Kite;
import io.kite.openai.OpenAiProvider;

/**
 * Agent-as-tool (delegation) pattern.
 *
 * <p>A {@code writer} agent delegates to a specialist {@code researcher} agent via
 * {@code researcher.asTool(...)}. Unlike routing ({@code .route()}), delegation does not
 * transfer control — the writer keeps the conversation, invokes the researcher with a fresh
 * prompt, gets a single atomic result back, and continues its own loop. The researcher runs
 * stateless: it sees only the {@code input} string, not the writer's prior turns.
 *
 * <p>Two variants below:
 * <ul>
 *   <li>{@code writer} — default delegation: the researcher's {@code reply.text()} becomes the
 *       tool output.</li>
 *   <li>{@code writerWithExtractor} — uses a {@code Function<Reply, String>} extractor to
 *       post-process the delegate's reply (trimming, reformatting, etc.).</li>
 * </ul>
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class DelegationSample {

    public static void main(String[] args) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null) {
            System.err.println("Set OPENAI_API_KEY to run this sample.");
            return;
        }

        try (var kite = Kite.builder()
                .provider(new OpenAiProvider(key))
                .build()) {

            var researcher = Agent.builder()
                    .model("gpt-4o-mini")
                    .name("researcher")
                    .description("Concise researcher; returns key facts only")
                    .instructions("Research the topic and return 3–5 concise bullet points. No prose.")
                    .build();

            // Variant 1 — default delegation: reply.text() is returned verbatim as the tool output.
            var writer = Agent.builder()
                    .model("gpt-4o-mini")
                    .name("writer")
                    .instructions("""
                            Write a 3-paragraph blog post. Call the researcher tool first to gather
                            facts, then write the post citing those facts.
                            """)
                    .tool(researcher.asTool(
                            "Research a topic and return 3–5 bullet points of findings"))
                    .build();

            System.out.println("=== Variant 1: default delegation ===");
            var reply1 = kite.run(writer, "Write a blog post about the James Webb Space Telescope.");
            System.out.println("Final agent: " + reply1.agent().name());
            System.out.println("Tokens: " + reply1.usage().totalTokens());
            System.out.println(reply1.text());

            // Variant 2 — custom output extractor: trim/prefix the researcher's reply before it
            // reaches the writer LLM. Useful for shaping sub-agent output when the default text
            // isn't quite what you want.
            var writerWithExtractor = Agent.builder()
                    .model("gpt-4o-mini")
                    .name("writer")
                    .instructions("""
                            Write a one-paragraph blog post. Call the research tool for facts.
                            """)
                    .tool(researcher.asTool(
                            "research",
                            "Research a topic and return findings",
                            r -> "RESEARCH_FINDINGS:\n" + (r.text() == null ? "" : r.text().trim())))
                    .build();

            System.out.println("\n=== Variant 2: custom output extractor ===");
            var reply2 = kite.run(writerWithExtractor, "Write a blog post about the Hubble Space Telescope.");
            System.out.println("Final agent: " + reply2.agent().name());
            System.out.println("Tokens: " + reply2.usage().totalTokens());
            System.out.println(reply2.text());
        }
    }
}
