package io.kite.internal.sse;

import java.util.function.BiConsumer;

/**
 * Minimal Server-Sent Events parser. Feeds raw text into an event-emitting state machine.
 * Stateful, single-threaded — one instance per stream.
 *
 * <p>Supports both SSE dialects used by LLM providers:
 * <ul>
 *   <li>OpenAI Chat Completions (legacy): unnamed {@code data: {...}\n\n} events</li>
 *   <li>OpenAI Responses API and Anthropic: named events {@code event: X\ndata: {...}\n\n}</li>
 * </ul>
 *
 * <p>The callback receives {@code (eventName, dataPayload)} where {@code eventName} may be null
 * for unnamed events. Comments ({@code :} lines), {@code id:} and {@code retry:} fields are ignored.
 *
 * <p>Allocations per emitted event: one {@code String} for the {@code data} payload. The
 * internal {@code StringBuilder}s are reused across events.
 */
public final class SseParser {

    private final StringBuilder dataBuf = new StringBuilder(512);
    private final StringBuilder lineBuf = new StringBuilder(256);
    private String eventName;

    /** Feed a chunk of text. Emits zero or more events to {@code onEvent}. */
    public void feed(CharSequence chunk, BiConsumer<String, String> onEvent) {
        for (int i = 0, n = chunk.length(); i < n; i++) {
            char c = chunk.charAt(i);
            if (c == '\n') {
                handleLine(onEvent);
                lineBuf.setLength(0);
            } else if (c != '\r') {
                lineBuf.append(c);
            }
        }
    }

    /** Feed a whole line without the trailing newline. Handy for {@code HttpClient.BodyHandlers.ofLines()}. */
    public void feedLine(CharSequence line, BiConsumer<String, String> onEvent) {
        for (int i = 0, n = line.length(); i < n; i++) {
            char c = line.charAt(i);
            if (c != '\r') lineBuf.append(c);
        }
        handleLine(onEvent);
        lineBuf.setLength(0);
    }

    private void handleLine(BiConsumer<String, String> onEvent) {
        if (lineBuf.length() == 0) {
            // Blank line — event boundary. Flush any accumulated data.
            if (dataBuf.length() > 0) {
                onEvent.accept(eventName, dataBuf.toString());
            }
            dataBuf.setLength(0);
            eventName = null;
            return;
        }
        if (lineBuf.charAt(0) == ':') {
            // Comment line — ignore (per spec).
            return;
        }
        if (startsWith(lineBuf, "data:")) {
            int start = 5;
            if (lineBuf.length() > 5 && lineBuf.charAt(5) == ' ') start = 6;
            if (dataBuf.length() > 0) dataBuf.append('\n');
            dataBuf.append(lineBuf, start, lineBuf.length());
        } else if (startsWith(lineBuf, "event:")) {
            int start = 6;
            if (lineBuf.length() > 6 && lineBuf.charAt(6) == ' ') start = 7;
            eventName = lineBuf.substring(start);
        }
        // id:, retry:, unknown fields — ignored
    }

    private static boolean startsWith(StringBuilder sb, String prefix) {
        if (sb.length() < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (sb.charAt(i) != prefix.charAt(i)) return false;
        }
        return true;
    }
}
