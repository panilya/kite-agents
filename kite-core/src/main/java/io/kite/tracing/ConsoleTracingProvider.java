package io.kite.tracing;

import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Human-readable tracing to stderr. Default in development. Prints one line per event.
 */
public final class ConsoleTracingProvider implements TracingProvider {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final PrintStream out;
    private final boolean verbose;

    public ConsoleTracingProvider() {
        this(System.err, false);
    }

    public ConsoleTracingProvider(boolean verbose) {
        this(System.err, verbose);
    }

    public ConsoleTracingProvider(PrintStream out, boolean verbose) {
        this.out = out;
        this.verbose = verbose;
    }

    @Override
    public void onRunStart(TraceContext ctx) {
        out.printf("[kite %s] run %s | %s%n", time(), shortId(ctx.traceId()), ctx.rootAgent());
    }

    @Override
    public void onRunEnd(TraceContext ctx) {
        if (verbose) out.printf("[kite %s] run %s done%n", time(), shortId(ctx.traceId()));
    }

    @Override
    public void onEvent(TraceContext ctx, TraceEvent event) {
        switch (event) {
            case TraceEvent.LlmRequest r -> {
                if (verbose) out.printf("[kite %s]   llm %s (request)%n", time(), r.agent());
            }
            case TraceEvent.LlmResponse r -> out.printf("[kite %s]   llm %s  %d+%d tok  %s%n",
                    time(), r.agent(), r.usage().promptTokens(), r.usage().completionTokens(), fmt(r.elapsed()));
            case TraceEvent.ToolCall c -> out.printf("[kite %s]   tool %s(%s)%n",
                    time(), c.toolName(), truncate(c.argsJson(), 80));
            case TraceEvent.ToolResult r -> out.printf("[kite %s]   tool %s → %s  %s%n",
                    time(), r.toolName(), truncate(r.resultJson(), 80), fmt(r.elapsed()));
            case TraceEvent.Transfer t -> out.printf("[kite %s]   transfer → %s%n", time(), t.to());
            case TraceEvent.GuardCheck g -> {
                var o = g.outcome();
                out.printf("[kite %s]   guard %s %s%s%n",
                        time(), o.name(), o.blocked() ? "BLOCK" : "PASS",
                        o.blocked() && o.info() != null ? " " + o.info() : "");
            }
            case TraceEvent.Error e -> out.printf("[kite %s]   ERROR %s: %s%n",
                    time(), e.agent(), e.message());
        }
    }

    private static String time() {
        return LocalTime.now().format(TIME);
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        int cut = id.indexOf('-');
        return cut > 0 ? id.substring(0, Math.min(cut + 5, id.length())) : id;
    }

    private static String fmt(Duration d) {
        long ms = d.toMillis();
        if (ms < 1000) return ms + "ms";
        return String.format("%.2fs", ms / 1000.0);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
