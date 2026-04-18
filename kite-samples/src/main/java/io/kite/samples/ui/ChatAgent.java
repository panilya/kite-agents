package io.kite.samples.ui;

import io.kite.Agent;
import io.kite.Guard;
import io.kite.Tool;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for the UI chatbot's agent. Builds an {@link Agent} with two tools
 * (one context-free, one context-aware) and three guards (two input, one output).
 *
 * <p>Kept provider-agnostic — {@link ChatServer} picks the model string that
 * matches whichever API key is set.
 */
final class ChatAgent {

    /** Per-request context passed into the agent. Also readable by context-aware tools. */
    record UserCtx(String userId, String displayName) {}

    private ChatAgent() {}

    static Agent<UserCtx> build(String model) {
        return Agent.builder(UserCtx.class)
                .model(model)
                .name("chat")
                .instructions("""
                        You are a concise, friendly assistant in a browser chat window.
                        Reply in plain text, no markdown fences. Keep answers short
                        unless the user asks for detail.

                        Tools:
                          - current_time: use when the user asks about time/date. Pass tz
                            when they name a city or IANA zone.
                          - get_profile: use when the user asks anything about themselves
                            ('me', 'my name', 'who am I'). Never ask the user for their id —
                            the tool returns it.
                        """)
                .instructions(ctx -> "Current user: " + ctx.displayName() + " (" + ctx.userId() + ").")
                .tool(currentTimeTool())
                .tool(getProfileTool())
                .inputGuards(List.of(noHackingGuard(), maxLengthGuard()))
                .outputGuards(List.of(noEmptyReplyGuard()))
                .build();
    }

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

    private static Guard<UserCtx> noHackingGuard() {
        return Guard.<UserCtx>inputTyped("no-hacking")
                .blocking()
                .check((ctx, input) -> input.toLowerCase().contains("hack")
                        ? Guard.block("I can't help with hacking.")
                        : Guard.pass());
    }

    private static Guard<UserCtx> maxLengthGuard() {
        return Guard.<UserCtx>inputTyped("max-length")
                .blocking()
                .check((ctx, input) -> input.length() > 2000
                        ? Guard.block("Message is too long (max 2000 chars).")
                        : Guard.pass());
    }

    private static Guard<UserCtx> noEmptyReplyGuard() {
        return Guard.<UserCtx>outputTyped("no-empty-reply")
                .check((ctx, output) -> (output == null || output.isBlank())
                        ? Guard.block("Model returned an empty response.")
                        : Guard.pass());
    }
}
