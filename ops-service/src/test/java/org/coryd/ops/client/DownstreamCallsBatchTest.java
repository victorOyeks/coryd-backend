package org.coryd.ops.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamCallsBatchTest {

    record Person(String id, String name) {
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode envelope(List<String> ids) {
        ObjectNode body = mapper.createObjectNode().put("success", true);
        ArrayNode data = body.putArray("data");
        ids.forEach(id -> data.addObject().put("id", id).put("name", "Name " + id));
        return body;
    }

    @Test
    void aBigLookupGoesOutInBatchesTheRequestLineCanHold() {
        List<String> ids = IntStream.range(0, 250).mapToObj(i -> "id-" + i).toList();
        List<Integer> sent = new ArrayList<>();

        Map<String, Person> found = DownstreamCalls.batch("profile-service", mapper, ids, Person.class, Person::id, batch -> {
            sent.add(batch.size());
            return envelope(batch);
        });

        assertThat(sent).containsExactly(100, 100, 50);
        assertThat(found).hasSize(250).containsKeys("id-0", "id-249");
    }

    @Test
    void aFailedBatchOnlyLeavesItsOwnIdsWithoutDetails() {
        List<String> ids = IntStream.range(0, 150).mapToObj(i -> "id-" + i).toList();

        Map<String, Person> found = DownstreamCalls.batch("profile-service", mapper, ids, Person.class, Person::id, batch -> {
            if (batch.contains("id-0")) {
                throw new IllegalStateException("400 Bad Request");
            }
            return envelope(batch);
        });

        assertThat(found).hasSize(50).containsKey("id-149").doesNotContainKey("id-0");
    }
}
