package org.coryd.ops.dto;

import org.coryd.ops.dto.safety.PersonSummary;

import java.time.Instant;

/**
 * One row of the console's First trip stories: someone's first drive or first ride and what they said about it.
 * feeling is LOVED_IT, GOOD, OKAY, NOT_GREAT or BAD; story may be null (a face picked, nothing written). consentToShare
 * true means CoRyd may quote it publicly.
 */
public record FirstTripStoryItem(String id, String role, PersonSummary person, String routePostId, String tripRef, String bookingId,
                                 String bookingRef, String originLabel, String destinationLabel, String feeling, String story,
                                 boolean consentToShare, Instant completedAt, Instant submittedAt) {
}
