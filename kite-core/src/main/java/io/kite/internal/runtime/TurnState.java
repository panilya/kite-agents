package io.kite.internal.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.kite.Agent;
import io.kite.model.Message;
import io.kite.model.Usage;

import java.util.List;

/**
 * Mutable per-run state carried through the turn loop: the currently active agent (which
 * may change on route transfer), the in-memory history, and the rolling accumulators for
 * usage and the last-seen assistant output. Replaces the handful of loose locals the
 * previous implementation threaded through its loop methods by hand.
 */
final class TurnState<T> {

    Agent<T> current;
    final List<Message> history;
    Usage usage = Usage.ZERO;
    String lastText = "";
    JsonNode lastStructured;
    boolean toolChoiceSatisfied;
    boolean firstTurn = true;

    TurnState(Agent<T> current, List<Message> history) {
        this.current = current;
        this.history = history;
    }
}
