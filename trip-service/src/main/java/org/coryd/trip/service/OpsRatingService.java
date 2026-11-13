package org.coryd.trip.service;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.RatingDirection;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.ops.OpsRatingDtos;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.OpsAuditLog;
import org.coryd.trip.entity.Rating;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RatingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ratings moderation. A hidden rating stays on record (and in this list) but counts in no average anywhere - search, the
 * trip screens, the receipts' driver stars, the console's directories - because every average is RatingRepository.summarize,
 * which skips hidden ratings. Hiding or restoring re-sends the rated person's stats so the directories follow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsRatingService {

    private final RatingRepository ratingRepository;
    private final BookingRepository bookingRepository;
    private final OpsAuditWriter auditWriter;
    private final DriverStatsPublisher driverStatsPublisher;
    private final RiderStatsPublisher riderStatsPublisher;

    @Transactional(readOnly = true)
    public PagedResponse<OpsRatingDtos.Row> list(OpsRatingDtos.Filter filter, Pageable pageable) {
        if (filter.maxStars() != null && (filter.maxStars() < 1 || filter.maxStars() > 5)
                || filter.minStars() != null && (filter.minStars() < 1 || filter.minStars() > 5)) {
            throw new BadRequestException("maxStars / minStars: must be 1 to 5");
        }
        Page<Rating> page = ratingRepository.findAll(spec(filter),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        Map<String, Booking> bookings = bookings(page.getContent());
        return PagedResponse.from(page.map(r -> row(r, r.getBookingId() == null ? null : bookings.get(r.getBookingId()))));
    }

    /** Visible ratings someone received - average, count, 1-5 distribution - and how many ops hid. */
    @Transactional(readOnly = true)
    public OpsRatingDtos.Summary summary(String userId, RatingDirection direction) {
        List<RatingRepository.RatingSummary> found = direction == null ? ratingRepository.summarize(List.of(userId))
                : ratingRepository.summarize(List.of(userId), direction);
        RatingRepository.RatingSummary s = found.isEmpty() ? null : found.getFirst();
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (int stars = 1; stars <= 5; stars++) {
            distribution.put(String.valueOf(stars), 0L);
        }
        ratingRepository.distribution(userId, direction).forEach(c -> distribution.put(String.valueOf(c.getStars()), c.getRatingCount()));
        return new OpsRatingDtos.Summary(userId, s == null || s.getAvgStars() == null ? null : Math.round(s.getAvgStars() * 100) / 100.0,
                s == null ? 0 : s.getRatingCount(), ratingRepository.countHidden(userId, direction), distribution);
    }

    @Transactional
    public OpsRatingDtos.ActionResult hide(String ratingId, OpsRatingDtos.ModerationRequest request) {
        String reason = require(request.reason());
        Rating rating = ratingRepository.findById(ratingId).orElseThrow(() -> new ResourceNotFoundException("Rating not found"));
        if (rating.isHidden()) {
            throw new BadRequestException("This rating is already hidden (by " + Objects.requireNonNullElse(rating.getHiddenByName(), "another admin") + ")");
        }
        rating.setHiddenAt(Instant.now());
        rating.setHiddenBy(request.actorId());
        rating.setHiddenByName(OpsSosService.name(request.actorId(), request.actorName()));
        rating.setHiddenReason(OpsAuditWriter.truncate(reason, 500));
        return act(rating, OpsAuditLog.Action.RATING_HIDDEN, false, request, reason);
    }

    @Transactional
    public OpsRatingDtos.ActionResult restore(String ratingId, OpsRatingDtos.ModerationRequest request) {
        String reason = require(request.reason());
        Rating rating = ratingRepository.findById(ratingId).orElseThrow(() -> new ResourceNotFoundException("Rating not found"));
        if (!rating.isHidden()) {
            throw new BadRequestException("This rating isn't hidden - there's nothing to restore");
        }
        rating.setHiddenAt(null);
        rating.setHiddenBy(null);
        rating.setHiddenByName(null);
        rating.setHiddenReason(null);
        return act(rating, OpsAuditLog.Action.RATING_RESTORED, true, request, reason);
    }

    private OpsRatingDtos.ActionResult act(Rating rating, OpsAuditLog.Action action, boolean wasHidden,
                                           OpsRatingDtos.ModerationRequest request, String reason) {
        ratingRepository.saveAndFlush(rating);
        Booking booking = rating.getBookingId() == null ? null : bookingRepository.findById(rating.getBookingId()).orElse(null);
        OpsAuditLog audit = auditWriter.write(request.actorId(), request.actorName(), action, OpsAuditLog.TargetType.RATING, rating.getId(),
                booking == null ? null : booking.getRoutePostId(), Map.of("hidden", wasHidden, "stars", rating.getStars()),
                Map.of("hidden", !wasHidden, "stars", rating.getStars()), reason);
        // Same stats the rating itself moved when it was given (BookingService.rate) - sent after commit.
        driverStatsPublisher.changed(rating.getRateeId());
        if (rating.getDirection() == RatingDirection.DRIVER_TO_RIDER) {
            riderStatsPublisher.changed(rating.getRateeId());
        }
        log.info("OPS {} {} rating {} ({} stars for {}): {}", request.actorId(), action, rating.getId(), rating.getStars(),
                rating.getRateeId(), reason);
        return new OpsRatingDtos.ActionResult(action.name(), audit.getId(), row(rating, booking),
                summary(rating.getRateeId(), rating.getDirection()));
    }

    private Map<String, Booking> bookings(List<Rating> ratings) {
        List<String> ids = ratings.stream().map(Rating::getBookingId).filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : bookingRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
    }

    private static OpsRatingDtos.Row row(Rating r, Booking booking) {
        return new OpsRatingDtos.Row(r.getId(), r.getBookingId(), booking == null ? null : booking.getRoutePostId(), r.getRaterId(),
                r.getRateeId(), r.getDirection(), r.getStars(), r.getComment(), r.getCreatedAt(), r.isHidden(), r.getHiddenAt(),
                r.getHiddenBy(), r.getHiddenByName(), r.getHiddenReason());
    }

    private static Specification<Rating> spec(OpsRatingDtos.Filter filter) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            // The seeded starting rating of every new profile isn't anyone's opinion - nothing to moderate.
            where.add(cb.isNotNull(root.get("bookingId")));
            if (filter.maxStars() != null) {
                where.add(cb.lessThanOrEqualTo(root.get("stars"), filter.maxStars()));
            }
            if (filter.minStars() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("stars"), filter.minStars()));
            }
            if (Boolean.TRUE.equals(filter.withComment())) {
                where.add(cb.isNotNull(root.get("comment")));
                where.add(cb.notEqual(cb.trim(root.get("comment")), ""));
            }
            if (filter.hidden() != null) {
                where.add(filter.hidden() ? cb.isNotNull(root.get("hiddenAt")) : cb.isNull(root.get("hiddenAt")));
            }
            if (filter.direction() != null) {
                where.add(cb.equal(root.get("direction"), filter.direction()));
            }
            if (filter.rateeId() != null && !filter.rateeId().isBlank()) {
                where.add(cb.equal(root.get("rateeId"), filter.rateeId().trim()));
            }
            if (filter.raterId() != null && !filter.raterId().isBlank()) {
                where.add(cb.equal(root.get("raterId"), filter.raterId().trim()));
            }
            if (filter.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), OpsSosService.startOfLagosDay(filter.from())));
            }
            if (filter.to() != null) {
                where.add(cb.lessThan(root.get("createdAt"), OpsSosService.startOfLagosDay(filter.to().plusDays(1))));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static String require(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("reason: is required");
        }
        return reason.trim();
    }
}
