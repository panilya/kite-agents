package io.kite.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecursiveRecordTest {

    record Tree(String name, List<Tree> kids) {}

    record Node(String label, Leaf leaf) {}
    record Leaf(String label, Node back) {}

    @Test
    void directSelfReferenceThrows() {
        assertThatThrownBy(() -> JsonSchemaGenerator.forRecord(Tree.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Recursive record not supported")
                .hasMessageContaining("Tree");
    }

    @Test
    void indirectCycleThrows() {
        assertThatThrownBy(() -> JsonSchemaGenerator.forRecord(Node.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Recursive record not supported")
                .hasMessageContaining("Node")
                .hasMessageContaining("Leaf");
    }
}
