package io.kite.samples;

import io.kite.Agent;
import io.kite.Kite;
import io.kite.Tool;
import io.kite.ToolChoice;
import io.kite.openai.OpenAiProvider;

/**
 * Forcing the model to call a specific named tool on the agent's first turn.
 *
 * <p>{@code ToolChoice.tool("store_ticket")} guarantees the {@code store_ticket} tool is called —
 * the model cannot respond in prose and cannot pick a different tool. The directive applies only
 * to the agent's first call; once {@code store_ticket} runs, follow-up turns revert to
 * {@code auto()} so the agent can summarize what it did instead of being forced to call the tool
 * again. The tool name is validated against the agent's tool+route set at {@code build()} time;
 * a typo fails fast with a clear error instead of hitting the provider API.
 *
 * <p>This is the canonical alternative to {@link BookingExtractor}'s structured-output approach:
 * use {@code .output(Class)} when you want a typed record back but don't need side effects; force
 * a tool call when the extraction must also <em>do something</em> (here, persist the ticket). The
 * tool's return value flows into the next turn's history, and the agent then summarizes what it
 * did in prose.
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class ForcedToolExtraction {

    public static void main(String[] args) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null) {
            System.err.println("Set OPENAI_API_KEY to run this sample.");
            return;
        }

        Tool storeTicket = Tool.create("store_ticket")
                .description("Persist a support ticket. Use this tool — do not reply in prose.")
                .param("summary", String.class, "One-sentence summary of the issue")
                .param("severity", String.class, "One of: low, medium, high, critical")
                .param("customerEmail", String.class, "Customer email address")
                .execute(toolArgs -> {
                    // A real implementation would write to a database.
                    String id = "T-" + (int) (Math.random() * 100000);
                    System.out.println("[store] wrote ticket " + id + ": " + toolArgs);
                    return "ticket_id=" + id;
                })
                .build();

        try (var kite = Kite.builder()
                .provider(new OpenAiProvider(key))
                .build()) {

            var agent = Agent.of("gpt-4o-mini")
                    .name("intake")
                    .instructions("Extract ticket details from the user's message and store them using the tool.")
                    .tool(storeTicket)
                    .toolChoice(ToolChoice.tool("store_ticket"))
                    .maxTurns(3)
                    .build();

            var reply = kite.run(agent,
                    "Hi, this is alice@example.com. Our production dashboard "
                            + "has been showing 502 errors since 9am — we can't see any metrics. "
                            + "This is blocking the whole on-call rotation.");

            System.out.println();
            System.out.println("Final agent reply:");
            System.out.println(reply.text());
        }
    }
}
