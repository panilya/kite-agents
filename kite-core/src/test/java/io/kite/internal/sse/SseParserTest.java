package io.kite.internal.sse;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseParserTest {

    @Test
    void parsesSingleUnnamedEvent() {
        var parser = new SseParser();
        List<String[]> events = new ArrayList<>();
        parser.feed("data: hello\n\n", (n, d) -> events.add(new String[]{n, d}));
        assertThat(events).hasSize(1);
        assertThat(events.get(0)[0]).isNull();
        assertThat(events.get(0)[1]).isEqualTo("hello");
    }

    @Test
    void parsesNamedEvent() {
        var parser = new SseParser();
        List<String[]> events = new ArrayList<>();
        parser.feed("event: content_block_delta\ndata: {\"a\":1}\n\n",
                (n, d) -> events.add(new String[]{n, d}));
        assertThat(events).hasSize(1);
        assertThat(events.get(0)[0]).isEqualTo("content_block_delta");
        assertThat(events.get(0)[1]).isEqualTo("{\"a\":1}");
    }

    @Test
    void parsesMultipleEventsAcrossOneFeed() {
        var parser = new SseParser();
        List<String[]> events = new ArrayList<>();
        parser.feed("data: one\n\ndata: two\n\ndata: three\n\n",
                (n, d) -> events.add(new String[]{n, d}));
        assertThat(events).hasSize(3);
        assertThat(events.get(0)[1]).isEqualTo("one");
        assertThat(events.get(1)[1]).isEqualTo("two");
        assertThat(events.get(2)[1]).isEqualTo("three");
    }

    @Test
    void handlesSplitFeedsAcrossChunkBoundary() {
        var parser = new SseParser();
        List<String[]> events = new ArrayList<>();
        parser.feed("data: hel", (n, d) -> events.add(new String[]{n, d}));
        parser.feed("lo\n\n", (n, d) -> events.add(new String[]{n, d}));
        assertThat(events).hasSize(1);
        assertThat(events.get(0)[1]).isEqualTo("hello");
    }

    @Test
    void ignoresCommentsAndUnknownFields() {
        var parser = new SseParser();
        List<String[]> events = new ArrayList<>();
        parser.feed(": keep-alive\nid: 42\nretry: 1000\ndata: payload\n\n",
                (n, d) -> events.add(new String[]{n, d}));
        assertThat(events).hasSize(1);
        assertThat(events.get(0)[1]).isEqualTo("payload");
    }

    @Test
    void concatenatesMultipleDataLinesWithNewline() {
        var parser = new SseParser();
        List<String[]> events = new ArrayList<>();
        parser.feed("data: line1\ndata: line2\n\n", (n, d) -> events.add(new String[]{n, d}));
        assertThat(events).hasSize(1);
        assertThat(events.get(0)[1]).isEqualTo("line1\nline2");
    }

    @Test
    void handlesCrlfLineEndings() {
        var parser = new SseParser();
        List<String[]> events = new ArrayList<>();
        parser.feed("event: x\r\ndata: crlf-payload\r\n\r\n",
                (n, d) -> events.add(new String[]{n, d}));
        assertThat(events).hasSize(1);
        assertThat(events.get(0)[0]).isEqualTo("x");
        assertThat(events.get(0)[1]).isEqualTo("crlf-payload");
    }

    @Test
    void feedLineSupportsJdkLineStreams() {
        var parser = new SseParser();
        List<String[]> events = new ArrayList<>();
        parser.feedLine("event: evt", (n, d) -> events.add(new String[]{n, d}));
        parser.feedLine("data: body", (n, d) -> events.add(new String[]{n, d}));
        parser.feedLine("", (n, d) -> events.add(new String[]{n, d}));
        assertThat(events).hasSize(1);
        assertThat(events.get(0)[0]).isEqualTo("evt");
        assertThat(events.get(0)[1]).isEqualTo("body");
    }
}
