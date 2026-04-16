package io.kite.samples;

import io.kite.Agent;
import io.kite.Kite;
import io.kite.ToolChoice;
import io.kite.annotations.Ctx;
import io.kite.annotations.Tool;
import io.kite.annotations.ToolParam;
import io.kite.openai.OpenAiProvider;

/**
 * Dynamic tool choice driven by the typed context.
 *
 * <p>The support agent has two tools: {@code self_service_article} (a cheap
 * knowledge-base lookup) and {@code escalate_to_human} (transfers the ticket to a
 * real agent). The {@code toolChoice(Function&lt;T, ToolChoice&gt;)} overload receives
 * the typed {@link SupportCtx} at request-build time and returns a different
 * directive per customer:
 *
 * <ul>
 *   <li><b>enterprise</b> customers must escalate immediately — their contract
 *       entitles them to a human within minutes.</li>
 *   <li><b>free</b> customers are forced to the self-service article — no human
 *       escalation is available on the free tier.</li>
 *   <li><b>standard</b> customers fall through to {@code auto()} and let the model
 *       decide.</li>
 * </ul>
 *
 * <p>The same agent definition serves all three tiers — the policy lives in the
 * context-driven function, not in three separate agents. The directive is resolved
 * per-call, so it reflects the tier passed to {@code kite.run} at that moment.
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class DynamicToolChoiceByTier {

    public record SupportCtx(String customerId, String tier) {}

    public static final class SupportTools {
        @Tool(description = "Look up a self-service help-center article")
        public String self_service_article(
                @Ctx SupportCtx ctx,
                @ToolParam(description = "Natural-language topic") String topic) {
            return "Found article: 'How to " + topic + "' — https://help.example.com/" + topic.replace(' ', '-');
        }

        @Tool(description = "Escalate the ticket to a human support agent")
        public String escalate_to_human(
                @Ctx SupportCtx ctx,
                @ToolParam(description = "One-sentence reason for escalation") String reason) {
            return "Ticket for " + ctx.customerId() + " escalated. Reason: " + reason
                    + ". A human will reach out shortly.";
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
                .build()) {

            var agent = Agent.of("gpt-4o-mini", SupportCtx.class)
                    .name("support")
                    .instructions(ctx -> "You are a support agent helping " + ctx.customerId()
                            + " (tier: " + ctx.tier() + ").")
                    .tools(new SupportTools())
                    .toolChoice((SupportCtx ctx) -> switch (ctx.tier()) {
                        case "enterprise" -> ToolChoice.tool("escalate_to_human");
                        case "free"       -> ToolChoice.tool("self_service_article");
                        default           -> ToolChoice.auto();
                    })
                    .maxTurns(3)
                    .build();

            String question = "My dashboard widgets are not loading, can you help?";

            for (String tier : new String[]{"enterprise", "standard", "free"}) {
                System.out.println("=== " + tier + " ===");
                var reply = kite.run(agent, question, new SupportCtx("C-" + tier, tier));
                System.out.println(reply.text());
                System.out.println();
            }
        }
    }
}
