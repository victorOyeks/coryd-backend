package org.coryd.trip.dto;

/** Cancellations Log tab badges by who cancelled - same filters as the list, except source. */
public record OpsCancellationCounts(
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
