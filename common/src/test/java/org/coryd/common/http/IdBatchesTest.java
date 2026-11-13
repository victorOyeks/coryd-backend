package org.coryd.common.http;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IdBatchesTest {

    @Test
    void splitsIntoBatchesSmallEnoughForOneRequestLine() {
        List<String> ids = IntStream.range(0, 250).mapToObj(i -> UUID.randomUUID().toString()).toList();

        List<List<String>> batches = IdBatches.of(ids);

        assertThat(batches).extracting(List::size).containsExactly(100, 100, 50);
        assertThat(batches.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(ids);
        // ?userIds=<uuid>&userIds=<uuid>... stays well under Tomcat's 8 KB request header limit.
        String query = String.join("&", batches.get(0).stream().map(id -> "userIds=" + id).toList());
        assertThat(query.length()).isLessThan(5_000);
    }

    @Test
    void dropsBlanksAndRepeatsAndCopesWithNothing() {
        assertThat(IdBatches.of(new ArrayList<>(Arrays.asList("a", null, "", " ", "b", "a")))).containsExactly(List.of("a", "b"));
        assertThat(IdBatches.of(List.of())).isEmpty();
        assertThat(IdBatches.of(null)).isEmpty();
        assertThat(IdBatches.of(List.of("a", "b", "c"), 2)).containsExactly(List.of("a", "b"), List.of("c"));
    }
}
