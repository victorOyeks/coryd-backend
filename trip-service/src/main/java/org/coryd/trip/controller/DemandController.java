package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.dto.DrivingPreferences;
import org.coryd.trip.dto.NotifyMeCreateRequest;
import org.coryd.trip.dto.NotifyMeResponse;
import org.coryd.trip.dto.RouteDemandItem;
import org.coryd.trip.service.DemandService;
import org.coryd.trip.service.NotifyMeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Rider demand: "Notify me" for riders, "Riders are looking for" for drivers. Always the signed-in user's own. */
@RestController
@RequestMapping("/routes")
@RequiredArgsConstructor
public class DemandController {

    private final NotifyMeService notifyMeService;
    private final DemandService demandService;

    /** One ride, or repeating (e.g. weekdays around 7 AM for 4 weeks). */
    @PostMapping("/notify-me")
    public ApiResponse<NotifyMeResponse> notifyMe(@AuthenticationPrincipal CoRydUserDetails principal,
                                                  @Valid @RequestBody NotifyMeCreateRequest request) {
        return ApiResponse.ok(notifyMeService.create(principal.getUserId(), request));
    }

    @GetMapping("/notify-me")
    public ApiResponse<List<NotifyMeResponse>> myNotifyMe(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(notifyMeService.mine(principal.getUserId()));
    }

    @DeleteMapping("/notify-me/{requestId}")
    public ApiResponse<Void> cancelNotifyMe(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String requestId) {
        notifyMeService.cancel(principal.getUserId(), requestId);
        return ApiResponse.ok("Removed", null);
    }

    /** Routes riders want, best fit for this driver first - totals only, never who. */
    @GetMapping("/demand")
    public ApiResponse<List<RouteDemandItem>> demand(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(demandService.demandFor(principal.getUserId()));
    }

    @GetMapping("/drivers/me/driving-preferences")
    public ApiResponse<DrivingPreferences> drivingPreferences(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(demandService.preferences(principal.getUserId()));
    }

    @PutMapping("/drivers/me/driving-preferences")
    public ApiResponse<DrivingPreferences> saveDrivingPreferences(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                  @RequestBody DrivingPreferences request) {
        return ApiResponse.ok(demandService.savePreferences(principal.getUserId(), request));
    }
}
