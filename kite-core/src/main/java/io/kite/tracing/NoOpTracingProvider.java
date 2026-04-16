package io.kite.tracing;

public final class NoOpTracingProvider implements TracingProvider {
    public static final NoOpTracingProvider INSTANCE = new NoOpTracingProvider();
    private NoOpTracingProvider() {}
}
