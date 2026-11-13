package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.RatingDirection;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.ops.OpsRatingDtos;
import org.coryd.trip.service.OpsRatingService;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Ratings moderation - service-to-service only (ops-service), gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/ops/ratings")
@RequiredArgsConstructor
public class InternalOpsRatingController {

    private final OpsRatingService service;

    @GetMapping
    public ApiResponse<PagedResponse<OpsRatingDtos.Row>> list(@RequestParam(required = false) Integer maxStars,
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
        return ApiResponse.ok(service.list(new OpsRatingDtos.Filter(maxStars, minStars, withComment, hidden, direction, rateeId, raterId,
                from, to), PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)))));
    }

    @GetMapping("/summary/{userId}")
    public ApiResponse<OpsRatingDtos.Summary> summary(@PathVariable String userId, @RequestParam(required = false) RatingDirection direction) {
        return ApiResponse.ok(service.summary(userId, direction));
    }

    @PostMapping("/{ratingId}/hide")
    public ApiResponse<OpsRatingDtos.ActionResult> hide(@PathVariable String ratingId, @Valid @RequestBody OpsRatingDtos.ModerationRequest request) {
        return ApiResponse.ok("Rating hidden", service.hide(ratingId, request));
    }

    @PostMapping("/{ratingId}/restore")
    public ApiResponse<OpsRatingDtos.ActionResult> restore(@PathVariable String ratingId, @Valid @RequestBody OpsRatingDtos.ModerationRequest request) {
        return ApiResponse.ok("Rating restored", service.restore(ratingId, request));
    }
}
