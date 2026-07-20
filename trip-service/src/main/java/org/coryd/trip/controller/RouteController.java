package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.trip.dto.PostRouteRequest;
import org.coryd.trip.dto.SearchRequest;
import org.coryd.trip.entity.NotifyMeRequest;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SuggestedFareRange;
import org.coryd.trip.service.RouteService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;

    @PostMapping("/drivers/{driverId}")
    public ApiResponse<RoutePost> post(@PathVariable String driverId, @Valid @RequestBody PostRouteRequest request) {
        return ApiResponse.ok(routeService.post(driverId, request));
    }

    @PostMapping("/search")
    public ApiResponse<List<RoutePost>> search(@Valid @RequestBody SearchRequest request) {
        return ApiResponse.ok(routeService.search(request));
    }

    @GetMapping("/suggested-fare")
    public ApiResponse<Optional<SuggestedFareRange>> suggestedFare(@RequestParam String originLabel,
                                                                     @RequestParam String destinationLabel) {
        return ApiResponse.ok(routeService.suggestedFare(originLabel, destinationLabel));
    }

    @PostMapping("/notify-me/riders/{riderId}")
    public ApiResponse<NotifyMeRequest> notifyMe(@PathVariable String riderId, @Valid @RequestBody SearchRequest request) {
        return ApiResponse.ok(routeService.requestNotification(riderId, request));
    }

    @DeleteMapping("/{routePostId}")
    public ApiResponse<Void> cancel(@PathVariable String routePostId) {
        routeService.cancel(routePostId);
        return ApiResponse.ok("Route cancelled", null);
    }
}
