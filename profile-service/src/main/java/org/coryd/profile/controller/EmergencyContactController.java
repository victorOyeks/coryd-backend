package org.coryd.profile.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.AddEmergencyContactRequest;
import org.coryd.profile.entity.EmergencyContact;
import org.coryd.profile.service.EmergencyContactService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/riders")
@RequiredArgsConstructor
public class EmergencyContactController {

    private final EmergencyContactService emergencyContactService;

    @GetMapping("/me/emergency-contacts")
    public ApiResponse<List<EmergencyContact>> list(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(emergencyContactService.getContacts(principal.getUserId()));
    }

    @PostMapping("/me/emergency-contacts")
    public ApiResponse<EmergencyContact> add(@AuthenticationPrincipal CoRydUserDetails principal,
                                              @Valid @RequestBody AddEmergencyContactRequest request) {
        return ApiResponse.ok(emergencyContactService.addContact(principal.getUserId(), request));
    }

    /** The rider checks one contact and taps Save - this is what that Save calls. */
    @PutMapping("/me/emergency-contacts/{contactId}/select")
    public ApiResponse<EmergencyContact> select(@AuthenticationPrincipal CoRydUserDetails principal,
                                                 @PathVariable String contactId) {
        return ApiResponse.ok(emergencyContactService.selectPrimary(principal.getUserId(), contactId));
    }

    /**
     * Service-to-service read (trip-service's Share Trip/SOS) - explicit userId, not /me, since
     * the caller here isn't necessarily the rider themselves (SOS can be triggered by the driver,
     * whose own forwarded JWT wouldn't resolve to the rider's contacts via @AuthenticationPrincipal).
     */
    @GetMapping("/{userId}/emergency-contacts")
    public ApiResponse<List<EmergencyContact>> listForUser(@PathVariable String userId) {
        return ApiResponse.ok(emergencyContactService.getContacts(userId));
    }
}
