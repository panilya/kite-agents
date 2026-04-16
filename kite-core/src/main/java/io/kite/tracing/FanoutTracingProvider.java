package io.kite.tracing;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forwards every event to every registered provider. Catches exceptions per-provider so a bad
 * downstream never breaks a run, and logs the first failure of each delegate to stderr so a
 * misconfigured tracer doesn't silently rot.
 */
public final class FanoutTracingProvider implements TracingProvider {

    private final List<TracingProvider> delegates;
    private final Set<Class<?>> loggedFailures = ConcurrentHashMap.newKeySet();

    public FanoutTracingProvider(List<TracingProvider> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void onRunStart(TraceContext ctx) {
        for (var d : delegates) safe(d, "onRunStart", () -> d.onRunStart(ctx));
    }

    @Override
    public void onRunEnd(TraceContext ctx) {
        for (var d : delegates) safe(d, "onRunEnd", () -> d.onRunEnd(ctx));
    }

    @Override
    public void onEvent(TraceContext ctx, TraceEvent event) {
        for (var d : delegates) safe(d, "onEvent", () -> d.onEvent(ctx, event));
    }

    @Override
    public void close() {
        for (var d : delegates) safe(d, "close", d::close);
    }

    private void safe(TracingProvider d, String op, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            if (loggedFailures.add(d.getClass())) {
                System.err.println("[kite] tracing provider " + d.getClass().getName()
                        + " failed in " + op + ": " + e.getClass().getSimpleName()
                        + ": " + e.getMessage() + " (further failures from this provider suppressed)");
            }
        }
    }
}
