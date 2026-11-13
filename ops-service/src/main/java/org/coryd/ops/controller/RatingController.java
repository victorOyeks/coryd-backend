package org.coryd.ops.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.RatingDirection;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.dto.safety.SafetyRequests;
import org.coryd.ops.dto.safety.SafetyViews;
import org.coryd.ops.service.RatingOpsService;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Ratings & quality - low ratings and comments, hide a rating (out of every average) and restore it. */
@RestController
@RequestMapping("/ratings")
@RequiredArgsConstructor
public class RatingController {

    private final RatingOpsService ratingOpsService;

    @GetMapping
    public ApiResponse<PagedResponse<SafetyViews.RatingItem>> list(@RequestParam(required = false) Integer maxStars,
                                                                   @RequestParam(required = false) Integer minStars,
                                                                   @RequestParam(required = false) Boolean withComment,
                                                                   @RequestParam(required = false) Boolean hidden,
                                                                   @RequestParam(required = false) RatingDirection direction,
                                                                   @RequestParam(required = false) String rateeId,
                                                                   @RequestParam(required = false) String raterId,
                                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size) {
        if (maxStars != null && (maxStars < 1 || maxStars > 5) || minStars != null && (minStars < 1 || minStars > 5)) {
            throw new BadRequestException("maxStars / minStars: must be 1 to 5");
        }
        return ApiResponse.ok(ratingOpsService.list(new RatingOpsService.Query(maxStars, minStars, withComment, hidden, direction, rateeId,
                raterId, from, to), PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)))));
    }

    /** One person's visible ratings: average, count, 1-5 distribution, and how many are hidden. */
    @GetMapping("/summary/{userId}")
    public ApiResponse<SafetyViews.RatingSummary> summary(@PathVariable String userId, @RequestParam(required = false) RatingDirection direction) {
        return ApiResponse.ok(ratingOpsService.summary(userId, direction));
    }

    @PostMapping("/{ratingId}/hide")
    public ApiResponse<SafetyViews.RatingActionResult> hide(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String ratingId,
                                                            @Valid @RequestBody SafetyRequests.RatingModeration request) {
        return ApiResponse.ok("Rating hidden", ratingOpsService.hide(ratingId, request, admin));
    }

    @PostMapping("/{ratingId}/restore")
    public ApiResponse<SafetyViews.RatingActionResult> restore(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String ratingId,
                                                               @Valid @RequestBody SafetyRequests.RatingModeration request) {
        return ApiResponse.ok("Rating restored", ratingOpsService.restore(ratingId, request, admin));
    }
}
