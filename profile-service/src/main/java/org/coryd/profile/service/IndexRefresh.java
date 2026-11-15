package org.coryd.profile.service;

/**
 * What one batch of the directory index backfill / nightly rebuild did: rows written, and rows that failed. Each row is
 * written in its own transaction, so a failure costs only that row - it's logged and counted, and the batch carries on.
 */
public record IndexRefresh(int indexed, int failed) {

    public static final IndexRefresh NONE = new IndexRefresh(0, 0);

    public IndexRefresh plus(IndexRefresh other) {
        return new IndexRefresh(indexed + other.indexed, failed + other.failed);
    }
}
