package io.kite.samples.research.tools;

import io.kite.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Factory for the {@code fetch_url} tool. Fetches a page, strips HTML, and returns plain text
 * the LLM can reason over. Declared {@code readOnly(true)} — safe to retry and safe to run
 * speculatively under parallel input guards.
 */
public final class FetchUrlTool {

    private static final int DEFAULT_MAX_CHARS = 4000;

    private FetchUrlTool() {}

    public static Tool create(FetchProvider provider) {
        return Tool.create("fetch_url")
                .description("Fetch a single URL and return its plain-text content. "
                        + "Prefer URLs that came from web_search results; if a fetch fails the LLM "
                        + "receives an ERROR string and should move on rather than retrying forever.")
                .param("url", String.class, "Absolute URL (http or https) to fetch.")
                .readOnly(true)
                .execute(args -> {
                    String url = (String) args.get("url");
                    String body = provider.fetch(url, DEFAULT_MAX_CHARS);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("url", url);
                    out.put("charCount", body.length());
                    out.put("content", body);
                    return out;
                })
                .build();
    }
}
