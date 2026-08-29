package org.coryd.profile.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
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

    @GetMapping("/batch")
    public ApiResponse<List<RiderSummary>> batch(@RequestParam List<String> userIds) {
        return ApiResponse.ok(riderProfileService.getBatch(userIds));
    }

    @GetMapping("/{userId}/emergency-contacts")
    public ApiResponse<List<EmergencyContact>> emergencyContacts(@PathVariable String userId) {
        return ApiResponse.ok(emergencyContactService.getContacts(userId));
    }
}
