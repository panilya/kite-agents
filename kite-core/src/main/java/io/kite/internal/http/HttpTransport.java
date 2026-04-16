package io.kite.internal.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Thin wrapper around {@link HttpClient} that offers two modes: {@link #postJson(URI, Map, String, Duration)}
 * for non-streaming and {@link #postJsonSse(URI, Map, String, Duration)} for SSE.
 *
 * <p>Shared across providers. Holds one client per instance; connection reuse is managed by
 * the JDK (HTTP/2 multiplexing on one connection per origin).
 */
public final class HttpTransport {

    private final HttpClient client;

    public HttpTransport() {
        this(Duration.ofSeconds(10));
    }

    public HttpTransport(Duration connectTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public HttpResponse<String> postJson(URI url, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest req = buildRequest(url, headers, body, timeout);
        try {
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpTransportException("HTTP request interrupted", e);
        } catch (Exception e) {
            throw new HttpTransportException("HTTP request failed: " + url, e);
        }
    }

    public HttpResponse<Stream<String>> postJsonSse(URI url, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest req = buildRequest(url, headers, body, timeout);
        try {
            return client.send(req, HttpResponse.BodyHandlers.ofLines());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpTransportException("HTTP stream request interrupted", e);
        } catch (Exception e) {
            throw new HttpTransportException("HTTP stream request failed: " + url, e);
        }
    }

    private HttpRequest buildRequest(URI url, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return b.build();
    }

    public static final class HttpTransportException extends RuntimeException {
        public HttpTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
