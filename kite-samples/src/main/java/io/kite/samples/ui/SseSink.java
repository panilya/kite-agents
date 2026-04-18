package io.kite.samples.ui;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes Server-Sent Events frames to an open HTTP response body. One frame per event:
 *
 * <pre>
 * event: delta
 * data: {"text":"hello"}
 *
 * </pre>
 *
 * <p>Not thread-safe: a single streaming run writes to one sink from one virtual thread.
 */
final class SseSink {

    private final OutputStream body;
    private boolean closed;

    SseSink(OutputStream body) {
        this.body = body;
    }

    /** Send a named SSE event with a single-line JSON payload. */
    void send(String event, String jsonData) throws IOException {
        if (closed) return;
        StringBuilder sb = new StringBuilder(event.length() + jsonData.length() + 16);
        sb.append("event: ").append(event).append('\n');
        sb.append("data: ").append(jsonData).append("\n\n");
        body.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        body.flush();
    }

    /** Send an unnamed comment (used as a keepalive / initial flush). */
    void comment(String text) throws IOException {
        if (closed) return;
        body.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
        body.flush();
    }

    void close() {
        if (closed) return;
        closed = true;
        try {
            body.close();
        } catch (IOException ignored) {
        }
    }
}
