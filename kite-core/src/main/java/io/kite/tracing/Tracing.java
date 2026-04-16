package io.kite.tracing;

import java.util.Arrays;

public final class Tracing {

    private Tracing() {}

    public static TracingProvider console() {
        return new ConsoleTracingProvider(false);
    }

    public static TracingProvider console(boolean verbose) {
        return new ConsoleTracingProvider(verbose);
    }

    public static TracingProvider off() {
        return NoOpTracingProvider.INSTANCE;
    }

    public static TracingProvider all(TracingProvider... providers) {
        return new FanoutTracingProvider(Arrays.asList(providers));
    }

    public static TracingProvider provider(TracingProvider p) {
        return p;
    }
}
