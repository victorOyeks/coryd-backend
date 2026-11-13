package org.coryd.profile.controller.internal;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.profile.dto.BillingContactResponse;
import org.coryd.profile.dto.RiderSummary;
import org.coryd.profile.entity.EmergencyContact;
import org.coryd.profile.service.EmergencyContactService;
import org.coryd.profile.service.RiderProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-to-service only (trip-service's ProfileServiceClient) - never called by a mobile client directly, gated by InternalApiKeyFilter via the /internal/** prefix and hidden from Swagger.
 * emergencyContacts is keyed by an explicit userId, not /me, since the caller here isn't necessarily the rider themselves (SOS can be triggered by the driver, whose own forwarded JWT wouldn't resolve to the rider's contacts via @AuthenticationPrincipal). */
@RestController
@RequestMapping("/internal/riders")
@RequiredArgsConstructor
public class InternalRiderController {

    private final RiderProfileService riderProfileService;
    private final EmergencyContactService emergencyContactService;
    private final org.coryd.profile.service.RiderIndexService riderIndexService;

    /** ops-service's Riders Directory - one indexed query: filter, sort and page in the database. */
    @GetMapping("/index")
    public ApiResponse<org.coryd.common.response.PagedResponse<org.coryd.profile.dto.RiderIndexView>> index(
            @RequestParam(required = false) org.coryd.common.enums.RiderDirectoryStatus status,
            @RequestParam(required = false) org.coryd.common.enums.RiderIdentityStatus identity,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) org.coryd.common.enums.RiderDirectorySort sort,
            @RequestParam(required = false) org.springframework.data.domain.Sort.Direction direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(riderIndexService.directory(status, identity, search, sort, direction, page, size));
    }

    @GetMapping("/index/counts")
    public ApiResponse<org.coryd.profile.dto.RiderIndexCounts> indexCounts(@RequestParam(required = false) String search) {
        return ApiResponse.ok(riderIndexService.counts(search));
    }

    @GetMapping("/index/{riderId}")
    public ApiResponse<org.coryd.profile.dto.RiderIndexView> indexedRider(@PathVariable String riderId) {
        return ApiResponse.ok(riderIndexService.rider(riderId));
    }

    @GetMapping("/{userId}/billing-contact")
    public ApiResponse<BillingContactResponse> billingContact(@PathVariable String userId) {
        return ApiResponse.ok(riderProfileService.getBillingContact(userId));
    }

    @GetMapping("/batch")
    public ApiResponse<List<RiderSummary>> batch(@RequestParam List<String> userIds) {
        return ApiResponse.ok(riderProfileService.getBatch(userIds));
    }

    @GetMapping("/{userId}/emergency-contacts")
    public ApiResponse<List<EmergencyContact>> emergencyContacts(@PathVariable String userId) {
        return ApiResponse.ok(emergencyContactService.getContacts(userId));
    }
}
