package io.kite.tracing;

public interface TracingProvider extends AutoCloseable {

    default void onRunStart(TraceContext ctx) {}

    default void onRunEnd(TraceContext ctx) {}

    default void onEvent(TraceContext ctx, TraceEvent event) {}

    @Override
    default void close() {}
}
