package org.coryd.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.RatingDirection;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.TripSafetyClient;
import org.coryd.ops.dto.safety.PersonSummary;
import org.coryd.ops.dto.safety.SafetyRequests;
import org.coryd.ops.dto.safety.SafetyViews;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ops Console - ratings moderation: low ratings and comments, hide (out of every average) and restore, with a reason. */
@Service
@RequiredArgsConstructor
public class RatingOpsService {

    private final TripSafetyClient tripSafetyClient;
    private final OpsPeople opsPeople;

    public record Query(Integer maxStars, Integer minStars, Boolean withComment, Boolean hidden, RatingDirection direction, String rateeId,
                        String raterId, LocalDate from, LocalDate to) {
        Map<String, Object> params() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("maxStars", maxStars);
            p.put("minStars", minStars);
            p.put("withComment", withComment);
            p.put("hidden", hidden);
            p.put("direction", direction);
            p.put("rateeId", rateeId);
            p.put("raterId", raterId);
            p.put("from", from);
            p.put("to", to);
            return p;
        }
    }

    public PagedResponse<SafetyViews.RatingItem> list(Query query, Pageable pageable) {
        Map<String, Object> params = query.params();
        params.put("page", pageable.getPageNumber());
        params.put("size", pageable.getPageSize());
        TripSafetyClient.RatingPage page = tripSafetyClient.get(TripSafetyClient.RatingPage.class, "/internal/ops/ratings", params);
        OpsPeople.Lookup people = people(page.content());
        return new PagedResponse<>(page.content().stream().map(r -> item(r, people)).toList(), page.page(), page.size(),
                page.totalElements(), page.totalPages(), page.last());
    }

    public SafetyViews.RatingSummary summary(String userId, RatingDirection direction) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("direction", direction);
        return summary(tripSafetyClient.get(TripSafetyClient.RatingSummary.class, "/internal/ops/ratings/summary/{userId}", params, userId));
    }

    public SafetyViews.RatingActionResult hide(String ratingId, SafetyRequests.RatingModeration request, CoRydUserDetails admin) {
        return action(ratingId, "hide", request, admin);
    }

    public SafetyViews.RatingActionResult restore(String ratingId, SafetyRequests.RatingModeration request, CoRydUserDetails admin) {
        return action(ratingId, "restore", request, admin);
    }

    private SafetyViews.RatingActionResult action(String ratingId, String verb, SafetyRequests.RatingModeration request, CoRydUserDetails admin) {
        Map<String, Object> body = SosOpsService.signed(admin);
        body.put("reason", request.reason().trim());
        TripSafetyClient.RatingActionResult r = tripSafetyClient.post(TripSafetyClient.RatingActionResult.class,
                "/internal/ops/ratings/{id}/" + verb, body, ratingId);
        return new SafetyViews.RatingActionResult(r.action(), r.auditId(), item(r.rating(), people(List.of(r.rating()))), summary(r.rateeSummary()));
    }

    private static SafetyViews.RatingSummary summary(TripSafetyClient.RatingSummary s) {
        return s == null ? null : new SafetyViews.RatingSummary(s.userId(), s.ratingAvg(), s.ratingCount(), s.hiddenCount(), s.distribution());
    }

    private OpsPeople.Lookup people(List<TripSafetyClient.RatingRow> rows) {
        List<String> drivers = new ArrayList<>();
        List<String> riders = new ArrayList<>();
        for (TripSafetyClient.RatingRow r : rows) {
            boolean riderRates = r.direction() == RatingDirection.RIDER_TO_DRIVER;
            (riderRates ? riders : drivers).add(r.raterId());
            (riderRates ? drivers : riders).add(r.rateeId());
        }
        return opsPeople.lookup(drivers, riders, List.of());
    }

    private static SafetyViews.RatingItem item(TripSafetyClient.RatingRow r, OpsPeople.Lookup people) {
        boolean riderRates = r.direction() == RatingDirection.RIDER_TO_DRIVER;
        PersonSummary rater = people.person(r.raterId(), riderRates ? "RIDER" : "DRIVER");
        PersonSummary ratee = people.person(r.rateeId(), riderRates ? "DRIVER" : "RIDER");
        return new SafetyViews.RatingItem(r.id(), r.bookingId(), TripOpsService.ref("B", r.bookingId()), r.routePostId(),
                TripOpsService.ref("T", r.routePostId()), r.direction(), r.stars(), r.comment(), r.createdAt(), rater, ratee, r.hidden(),
                r.hiddenAt(), SosOpsService.admin(r.hiddenBy(), r.hiddenByName()), r.hiddenReason());
    }
}
