package io.kite.internal.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Thin Jackson facade. One shared ObjectMapper per JVM; unchecked exceptions wrap
 * JsonProcessingException so callers can treat JSON failures as programming errors.
 *
 * <p>This class is intentionally minimal. If Jackson ever needs to be swapped, this is the
 * only file that changes.
 */
public final class JsonCodec {

    private static final JsonCodec SHARED = new JsonCodec(buildDefaultMapper());

    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static JsonCodec shared() {
        return SHARED;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("Failed to parse JSON: " + truncate(json), e);
        }
    }

    /** Parse JSON, returning an empty object node when the input is null or empty. */
    public JsonNode readTreeOrEmpty(String json) {
        return json == null || json.isEmpty() ? mapper.createObjectNode() : readTree(json);
    }

    public String writeValueAsString(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("Failed to serialize value of " + (value == null ? "null" : value.getClass()), e);
        }
    }

    public <T> T treeToValue(JsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("Failed to bind JSON to " + type.getName(), e);
        }
    }

    public JsonNode valueToTree(Object value) {
        return mapper.valueToTree(value);
    }

    private static ObjectMapper buildDefaultMapper() {
        return JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    public static final class JsonCodecException extends RuntimeException {
        public JsonCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
