package io.kite;

public record AgentRef(String name, String model) {
    public static final AgentRef UNKNOWN = new AgentRef("<unknown>", "<unknown>");
}
