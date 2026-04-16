package io.kite;

import io.kite.internal.runtime.MockModelProvider;
import io.kite.model.Message;
import io.kite.tracing.Tracing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimePersistenceTest {

    @Test
    void historyPersistsAcrossOkTurnsForSameConversationId() {
        var mock = MockModelProvider.builder()
                .respondText("hi back")
                .respondText("still here")
                .build();
        var store = new RecordingStore();
        var kite = Kite.builder().provider(mock).conversationStore(store).tracing(Tracing.off()).build();
        var agent = Agent.builder().model("gpt-test").build();

        kite.run(agent, "hi", "conv-1");
        kite.run(agent, "again", "conv-1");

        var second = mock.recorded().get(1);
        long userMsgs = second.messages().stream().filter(m -> m instanceof Message.User).count();
        assertThat(userMsgs).as("second turn must see prior user message + new").isEqualTo(2);
        kite.close();
    }

    @Test
    void historyPersistsOnMaxTurns() {
        // Set up a loop that always emits a tool call so it never terminates → MAX_TURNS.
        var mock = MockModelProvider.builder()
                .respondToolCall("c1", "noop", "{}")
                .respondToolCall("c2", "noop", "{}")
                .respondToolCall("c3", "noop", "{}")
                .build();
        var store = new RecordingStore();
        var kite = Kite.builder().provider(mock).conversationStore(store).tracing(Tracing.off()).maxTurns(2).build();
        var agent = Agent.builder().model("gpt-test")
                .tool(Tool.create("noop").description("noop").execute(args -> "ok").build())
                .build();

        Reply reply = kite.run(agent, "hi", "conv-mx");

        assertThat(reply.status()).isEqualTo(Status.MAX_TURNS);
        assertThat(store.saved.get("conv-mx")).as("MAX_TURNS run must persist history").isNotNull();
        kite.close();
    }

    @Test
    void historyNotPersistedOnBlocked() {
        var mock = MockModelProvider.builder().build();
        var store = new RecordingStore();
        var kite = Kite.builder().provider(mock).conversationStore(store).tracing(Tracing.off()).build();
        var blocker = Guard.input("blk").blocking().check((c, in) -> Guard.block("nope"));
        var agent = Agent.builder().model("gpt-test").inputGuards(List.of(blocker)).build();

        kite.run(agent, "hi", "conv-bk");

        assertThat(store.saved.get("conv-bk")).as("BLOCKED runs must not poison history").isNull();
        kite.close();
    }

    static final class RecordingStore implements ConversationStore {
        final ConcurrentHashMap<String, List<Message>> saved = new ConcurrentHashMap<>();

        @Override public List<Message> load(String id) {
            return saved.getOrDefault(id, List.of());
        }
        @Override public void save(String id, List<Message> updated) {
            saved.put(id, new ArrayList<>(updated));
        }
    }
}
