package org.coryd.trip.dto.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.RatingDirection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** Ratings moderation as trip-service serves it to ops-service (internal only). */
public final class OpsRatingDtos {

    private OpsRatingDtos() {
    }

    public record Filter(Integer maxStars, Integer minStars, Boolean withComment, Boolean hidden, RatingDirection direction,
                         String rateeId, String raterId, LocalDate from, LocalDate to) {
    }

    public record Row(String id, String bookingId, String routePostId, String raterId, String rateeId, RatingDirection direction,
                      int stars, String comment, Instant createdAt, boolean hidden, Instant hiddenAt, String hiddenBy,
                      String hiddenByName, String hiddenReason) {
    }

    /** Visible ratings only; hidden counted apart. distribution is "1".."5" to how many. */
    public record Summary(String userId, Double ratingAvg, long ratingCount, long hiddenCount, Map<String, Long> distribution) {
    }

    public record ActionResult(String action, String auditId, Row rating, Summary rateeSummary) {
    }

    public record ModerationRequest(@NotBlank(message = "is required") @Size(max = 500) String reason,
                                    @NotBlank(message = "is required") String actorId, @Size(max = 120) String actorName) {
    }
}
