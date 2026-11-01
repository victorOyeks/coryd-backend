package org.coryd.profile.controller.internal;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.profile.dto.DriverOnboardingStatusResponse;
import org.coryd.profile.dto.DriverPrerequisiteStatus;
import org.coryd.profile.dto.DriverSummary;
import org.coryd.profile.dto.RiderSummary;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.service.DriverProfileService;
import org.coryd.profile.service.FavoriteDriverService;
import org.coryd.profile.service.VerificationAggregationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-to-service only (trip-service's ProfileServiceClient) - never called by a mobile client directly, gated by InternalApiKeyFilter via the /internal/** prefix and hidden from Swagger. */
@RestController
@RequestMapping("/internal/drivers")
@RequiredArgsConstructor
public class InternalDriverController {

    private final DriverProfileService driverProfileService;
    private final FavoriteDriverService favoriteDriverService;
    private final VerificationAggregationService verificationAggregationService;
    private final org.coryd.profile.service.DriverIndexService driverIndexService;

    @GetMapping("/{userId}")
    public ApiResponse<DriverProfile> get(@PathVariable String userId) {
        return ApiResponse.ok(driverProfileService.get(userId));
    }

    /** trip-service's route-posting gate - identity and vehicle, both submitted and approved. */
    @GetMapping("/{userId}/posting-prerequisites")
    public ApiResponse<DriverPrerequisiteStatus> postingPrerequisites(@PathVariable String userId) {
        return ApiResponse.ok(verificationAggregationService.getPrerequisiteStatus(userId));
    }

    /** Driver app's onboarding checklist - submission state, not approval. */
    @GetMapping("/{userId}/onboarding-status")
    public ApiResponse<DriverOnboardingStatusResponse> onboardingStatus(@PathVariable String userId) {
        return ApiResponse.ok(verificationAggregationService.getOnboardingStatus(userId));
    }

    /** ops-service's Drivers Directory - one indexed query: filter, sort and page in the database. */
    @GetMapping("/index")
    public ApiResponse<org.coryd.common.response.PagedResponse<org.coryd.profile.dto.DriverIndexView>> index(
            @RequestParam(required = false) org.coryd.common.enums.DriverDirectoryStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) org.coryd.common.enums.DriverDirectorySort sort,
            @RequestParam(required = false) org.springframework.data.domain.Sort.Direction direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(driverIndexService.directory(status, search, sort, direction, page, size));
    }

    @GetMapping("/index/counts")
    public ApiResponse<org.coryd.profile.dto.DriverIndexCounts> indexCounts(@RequestParam(required = false) String search) {
        return ApiResponse.ok(driverIndexService.counts(search));
    }

    @GetMapping("/index/{driverId}")
    public ApiResponse<org.coryd.profile.dto.DriverIndexView> indexedDriver(@PathVariable String driverId) {
        return ApiResponse.ok(driverIndexService.driver(driverId));
    }

    @GetMapping("/batch")
    public ApiResponse<List<DriverSummary>> batch(@RequestParam List<String> userIds) {
        return ApiResponse.ok(driverProfileService.getBatch(userIds));
    }

    /** trip-service's reserved-seats validation - never trust a client-claimed "this rider
     * favourited me" list without checking it against the real relationship. */
    @GetMapping("/{driverId}/favorited-by")
    public ApiResponse<List<RiderSummary>> favoritedBy(@PathVariable String driverId) {
        return ApiResponse.ok(favoriteDriverService.listRidersWhoFavourited(driverId));
    }
}
