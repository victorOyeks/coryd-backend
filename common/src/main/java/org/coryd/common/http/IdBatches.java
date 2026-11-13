package org.coryd.common.http;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Splits a list of ids for a batch lookup sent as repeated query parameters (?userIds=a&userIds=b...).
 *
 * <p>Each UUID costs about 45 characters of request line, and Tomcat refuses a request whose line and headers pass 8 KB
 * with a bare 400 before any CoRyd code runs - so 200 ids in one GET never arrive. Callers treat a failed lookup as
 * "no details", which hid this: the nightly index rebuild (200 riders a batch) quietly lost every phone, suspension and
 * join date. {@link #MAX_IDS} keeps each request near 4.5 KB.
 */
public final class IdBatches {

    public static final int MAX_IDS = 100;

    private IdBatches() {
    }

    /** The distinct, non-blank ids in order, in lists of at most MAX_IDS. */
    public static List<List<String>> of(Collection<String> ids) {
        return of(ids, MAX_IDS);
    }

    static List<List<String>> of(Collection<String> ids, int max) {
        List<String> distinct = ids == null ? List.of()
                : ids.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < distinct.size(); from += max) {
            batches.add(distinct.subList(from, Math.min(from + max, distinct.size())));
        }
        return batches;
    }
}
