package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.dto.RouteTemplateRequest;
import org.coryd.trip.dto.RouteTemplateResponse;
import org.coryd.trip.service.RouteTemplateService;
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

/** A driver's regular routes - always the caller's own. Saving one posts its next trips straight away. */
@RestController
@RequestMapping("/routes/templates")
@RequiredArgsConstructor
public class RouteTemplateController {

    private final RouteTemplateService routeTemplateService;

    @GetMapping
    public ApiResponse<List<RouteTemplateResponse>> mine(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(routeTemplateService.mine(principal.getUserId()));
    }

    @PostMapping
    public ApiResponse<RouteTemplateResponse> create(@AuthenticationPrincipal CoRydUserDetails principal,
                                                     @Valid @RequestBody RouteTemplateRequest request) {
        RouteTemplateResponse created = routeTemplateService.create(principal.getUserId(), request);
        routeTemplateService.postUpcomingTrips(principal.getUserId(), created.id());
        return ApiResponse.ok(created);
    }

    @PutMapping("/{templateId}")
    public ApiResponse<RouteTemplateResponse> update(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String templateId,
                                                     @Valid @RequestBody RouteTemplateRequest request) {
        RouteTemplateResponse updated = routeTemplateService.update(principal.getUserId(), templateId, request);
        routeTemplateService.postUpcomingTrips(principal.getUserId(), templateId);
        return ApiResponse.ok(updated);
    }

    @PostMapping("/{templateId}/pause")
    public ApiResponse<RouteTemplateResponse> pause(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String templateId) {
        return ApiResponse.ok(routeTemplateService.setPaused(principal.getUserId(), templateId, true));
    }

    @PostMapping("/{templateId}/resume")
    public ApiResponse<RouteTemplateResponse> resume(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String templateId) {
        RouteTemplateResponse resumed = routeTemplateService.setPaused(principal.getUserId(), templateId, false);
        routeTemplateService.postUpcomingTrips(principal.getUserId(), templateId);
        return ApiResponse.ok(resumed);
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String templateId) {
        routeTemplateService.delete(principal.getUserId(), templateId);
        return ApiResponse.ok("Regular route removed", null);
    }
}
