package io.kite.model;

import java.util.List;

public sealed interface Message
        permits Message.System, Message.User, Message.Assistant, Message.Tool {

    record System(String content) implements Message {}

    record User(String content) implements Message {}

    record Assistant(String content, List<ToolCallRef> toolCalls) implements Message {
        public Assistant {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    record Tool(String toolCallId, String name, String resultJson) implements Message {}

    record ToolCallRef(String id, String name, String argsJson) {}
}
