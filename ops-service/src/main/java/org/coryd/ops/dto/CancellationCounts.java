package org.coryd.ops.dto;

/** Cancellations Log tab badges - same filters as the list, except source. */
public record CancellationCounts(
        long all,
        long rider,
        long driver,
        long systemExpiry,
        long system,
        long ops,
        long suspension,
        long accountDeleted
) {
}
