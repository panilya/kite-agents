package io.kite.samples.research.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Live URL fetcher. Performs a plain GET, strips HTML tags with a coarse regex, collapses
 * whitespace, and truncates. Any error is returned as a short descriptive string so the LLM
 * sees a useful tool result instead of crashing the run.
 */
public final class RealFetchProvider implements FetchProvider {

    private final HttpClient http;

    public RealFetchProvider() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String fetch(String url, int maxChars) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "kite-samples/research (+github.com)")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return "ERROR: HTTP " + resp.statusCode() + " fetching " + url;
            }
            String stripped = resp.body()
                    .replaceAll("(?is)<script.*?>.*?</script>", " ")
                    .replaceAll("(?is)<style.*?>.*?</style>", " ")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (stripped.length() > maxChars) stripped = stripped.substring(0, maxChars) + "…";
            return stripped;
        } catch (Exception e) {
            return "ERROR: " + e.getClass().getSimpleName() + " fetching " + url + ": " + e.getMessage();
        }
    }
}
