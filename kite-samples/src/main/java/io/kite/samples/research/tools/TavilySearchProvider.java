package io.kite.samples.research.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Tavily {@code /search} client. Sends the API key in the request body so the same code
 * works against the legacy and current endpoint variants. Any non-2xx response degrades to an
 * empty result list rather than throwing — the sample then continues with whatever other
 * subtopics did return data, which mirrors how deep-research agents handle partial failures.
 */
public final class TavilySearchProvider implements SearchProvider {

    private static final String ENDPOINT = "https://api.tavily.com/search";

    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper json;

    public TavilySearchProvider(String apiKey) {
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.json = new ObjectMapper();
    }

    @Override
    public List<SearchHit> search(String query, int maxResults) {
        try {
            ObjectNode body = json.createObjectNode();
            body.put("api_key", apiKey);
            body.put("query", query);
            body.put("max_results", Math.max(1, Math.min(maxResults, 10)));
            body.put("search_depth", "basic");

            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return List.of();

            JsonNode root = json.readTree(resp.body());
            JsonNode results = root.path("results");
            if (!results.isArray()) return List.of();

            List<SearchHit> out = new ArrayList<>(results.size());
            for (JsonNode r : results) {
                String title = r.path("title").asText("");
                String url = r.path("url").asText("");
                String content = r.path("content").asText("");
                if (url.isEmpty()) continue;
                out.add(new SearchHit(title, url, truncate(content, 400)));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
