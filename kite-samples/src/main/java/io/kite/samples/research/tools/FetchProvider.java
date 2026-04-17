package io.kite.samples.research.tools;

/**
 * Pluggable URL fetcher. {@link RealFetchProvider} hits the live web via {@link java.net.http.HttpClient};
 * {@link MockFetchProvider} returns canned markdown-like text for {@code example.com/research/*}
 * URLs produced by {@link MockSearchProvider} so the sample can complete end-to-end offline.
 */
public interface FetchProvider {
    /** Returns plain-text body (≤ {@code maxChars}) or a short error description — never throws. */
    String fetch(String url, int maxChars);
}
