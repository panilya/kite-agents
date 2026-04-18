package io.kite.samples.tools;

import io.kite.Agent;
import io.kite.Kite;
import io.kite.Tool;
import io.kite.anthropic.AnthropicProvider;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Programmatic tool construction via {@link Tool#create(String)}. Use this when annotating a
 * class isn't practical — tools discovered at startup from config, plugins, or external
 * schemas.
 *
 * <p>Two tools are built here, one of each execution flavour:
 * <ul>
 *   <li>{@code current_time} — context-free, via {@link io.kite.ToolBuilder#execute}. Has an
 *       optional {@code tz} parameter declared with {@code optionalParam(...)}.
 *   <li>{@code get_profile} — context-aware, via {@link io.kite.ToolBuilder#executeWithContext}.
 *       Reads {@link UserCtx} at call time; the {@code ctx} is injected by Kite and is
 *       <em>not</em> visible in the tool's JSON schema.
 * </ul>
 *
 * <p>Run with {@code ANTHROPIC_API_KEY} set.
 */
public final class ProgrammaticToolsAgent {

    public record UserCtx(String userId, String displayName) {}

    private static Tool currentTimeTool() {
        return Tool.create("current_time")
                .description("Return the current wall-clock time. Optional IANA timezone id.")
                .optionalParam("tz", String.class, "IANA timezone id (e.g. 'Europe/Warsaw'). Defaults to UTC.")
                .execute(args -> {
                    String tz = (String) args.get("tz");
                    ZoneId zone = tz == null ? ZoneId.of("UTC") : ZoneId.of(tz);
                    ZonedDateTime now = ZonedDateTime.now(zone);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("zone", zone.getId());
                    out.put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    return out;
                })
                .build();
    }

    private static Tool getProfileTool() {
        return Tool.create("get_profile")
                .description("Return the current user's profile. 'format' picks short or full.")
                .param("format", String.class, "'short' or 'full'; defaults to 'short'.", false, "short")
                .<UserCtx, Map<String, Object>>executeWithContext((ctx, args) -> {
                    String format = (String) args.get("format");
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("userId", ctx.userId());
                    out.put("displayName", ctx.displayName());
                    if ("full".equals(format)) {
                        out.put("joined", "2024-01-15");
                        out.put("tier", "premium");
                    }
                    return out;
                })
                .build();
    }

    public static void main(String[] args) {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null) {
            System.err.println("Set ANTHROPIC_API_KEY to run this sample.");
            return;
        }

        try (var kite = Kite.builder()
                .provider(new AnthropicProvider(key))
                .build()) {

            var agent = Agent.builder(UserCtx.class)
                    .model("claude-sonnet-4-6")
                    .name("profile-bot")
                    .instructions("Use the tools. get_profile returns the current user — you "
                            + "already have access, so never ask the user for their id. "
                            + "current_time returns a wall clock; pass the tz when the user names one.")
                    .tool(currentTimeTool())
                    .tool(getProfileTool())
                    .build();

            System.out.println("Tools discovered:");
            for (var t : agent.tools()) {
                System.out.println("  " + t.name() + "  " + t.paramsSchema().writeJson());
            }
            System.out.println();

            var ctx = new UserCtx("U-42", "Ada");
            var reply = kite.run(agent,
                    "What's my display name, and what time is it in Europe/Warsaw right now?",
                    ctx);
            System.out.println(reply.text());
        }
    }
}
