package io.kite.tracing;

import java.util.List;

/**
 * Forwards every event to every registered provider. Swallows exceptions per-provider so a bad
 * downstream never breaks a run.
 */
public final class FanoutTracingProvider implements TracingProvider {

    private final List<TracingProvider> delegates;

    public FanoutTracingProvider(List<TracingProvider> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void onRunStart(TraceContext ctx) {
        for (var d : delegates) {
            try { d.onRunStart(ctx); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onRunEnd(TraceContext ctx) {
        for (var d : delegates) {
            try { d.onRunEnd(ctx); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onEvent(TraceContext ctx, TraceEvent event) {
        for (var d : delegates) {
            try { d.onEvent(ctx, event); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void close() {
        for (var d : delegates) {
            try { d.close(); } catch (Throwable ignored) {}
        }
    }
}
