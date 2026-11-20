package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.FirstTripDtos;
import org.coryd.trip.entity.FirstTripExperience;
import org.coryd.trip.service.FirstTripService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** First trip stories for the console - service-to-service only (ops-service), gated by InternalApiKeyFilter via /internal/**. */
@Hidden
@RestController
@RequestMapping("/internal/ops/first-trip-stories")
@RequiredArgsConstructor
public class InternalOpsFirstTripController {

    private final FirstTripService service;

    @GetMapping
    public ApiResponse<PagedResponse<FirstTripDtos.OpsRow>> list(@RequestParam(required = false) FirstTripExperience.Role role,
                                                                 @RequestParam(required = false) Boolean consent,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.listShared(role, consent, page, size));
    }
}
