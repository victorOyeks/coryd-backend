package org.coryd.trip.dto;

/** How a demanded route fits the trips this driver makes - the order the list is sorted in. */
public enum DemandRelevance {
    /** Same start, same end, same time of day as trips they drive. */
    MATCHES_YOUR_ROUTES,
    /** Same start and end, but a different time of day. */
    DIFFERENT_TIME,
    /** Starts where they start. */
    SAME_START,
    /** Ends where they end. */
    SAME_END,
    OTHER
}
