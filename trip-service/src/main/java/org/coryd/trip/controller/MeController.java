package org.coryd.trip.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.dto.RecentDestination;
import org.coryd.trip.service.RecentDestinationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The caller's own trip data - always self-scoped from the JWT. */
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {

    private final RecentDestinationService recentDestinationService;

    /** Places the caller rode to or drove to lately, newest first - riders and drivers alike. */
    @GetMapping("/recent-destinations")
    public ApiResponse<List<RecentDestination>> recentDestinations(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                   @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.ok(recentDestinationService.recent(principal.getUserId(), limit));
    }
}
