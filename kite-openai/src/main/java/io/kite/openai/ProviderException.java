package io.kite.openai;

/** Thrown when a provider returns a non-2xx HTTP status or the body cannot be parsed. */
public final class ProviderException extends RuntimeException {

    private final String provider;
    private final int statusCode;
    private final String body;

    public ProviderException(String provider, int statusCode, String body) {
        super(provider + " returned HTTP " + statusCode + ": " + truncate(body));
        this.provider = provider;
        this.statusCode = statusCode;
        this.body = body;
    }

    public String provider() { return provider; }
    public int statusCode() { return statusCode; }
    public String body() { return body; }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }
}
