package org.coryd.trip.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.trip.entity.FirstTripExperience;

import java.time.Instant;

/** First trip celebration: what the app asks, what it sends back, and the console's row. */
public final class FirstTripDtos {

    private FirstTripDtos() {
    }

    /** "Celebrate?" - present only while the person's first trip in this role waits for a share or a skip. */
    public record Celebration(String id, FirstTripExperience.Role role, String routePostId, String bookingId,
                              String originLabel, String destinationLabel, Instant completedAt) {
    }

    public record ShareRequest(
            @NotNull(message = "Pick how it went") FirstTripExperience.Feeling feeling,
            @Size(max = 500, message = "Keep it under 500 characters") String story,
            Boolean consentToShare) {
    }

    /** One shared experience, for ops-service's First trip stories list. */
    public record OpsRow(String id, String userId, FirstTripExperience.Role role, String routePostId, String bookingId,
                         String originLabel, String destinationLabel, FirstTripExperience.Feeling feeling, String story,
                         boolean consentToShare, Instant completedAt, Instant submittedAt) {
    }
}
