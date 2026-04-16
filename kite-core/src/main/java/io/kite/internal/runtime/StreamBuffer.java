package io.kite.internal.runtime;

import io.kite.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Holds events in memory while parallel guards resolve in BUFFER mode. Once the guards complete,
 * either {@link #flushAndSeal(Consumer)} (all pass) or {@link #discardAndSeal()} (any block) is called.
 * After sealing, subsequent events pass straight through to the downstream consumer.
 *
 * <p>Thread-safe via coarse intrinsic locking. Not a hot path — only runs once per streaming call.
 */
public final class StreamBuffer {

    private final List<Event> held = new ArrayList<>(64);
    private boolean sealed;
    private final Consumer<Event> downstream;

    public StreamBuffer(Consumer<Event> downstream) {
        this.downstream = downstream;
    }

    public synchronized void accept(Event event) {
        if (sealed) {
            downstream.accept(event);
        } else {
            held.add(event);
        }
    }

    public synchronized void flushAndSeal(Consumer<Event> out) {
        for (var e : held) out.accept(e);
        held.clear();
        sealed = true;
    }

    public synchronized void discardAndSeal() {
        held.clear();
        sealed = true;
    }

    public synchronized boolean sealed() {
        return sealed;
    }
}
