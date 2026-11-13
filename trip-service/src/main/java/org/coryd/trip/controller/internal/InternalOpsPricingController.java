package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.trip.dto.OpsPricingResponse;
import org.coryd.trip.service.OpsPricingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Ops Console pricing monitor - ops-service only, behind InternalApiKeyFilter. */
@Hidden
@RestController
@RequestMapping("/internal/ops/pricing")
@RequiredArgsConstructor
public class InternalOpsPricingController {

    private final OpsPricingService opsPricingService;

    @GetMapping
    public ApiResponse<OpsPricingResponse> pricing(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(required = false) String corridor,
                                                   @RequestParam(defaultValue = "false") boolean outliersOnly) {
        return ApiResponse.ok(opsPricingService.pricing(from, to, corridor, outliersOnly));
    }
}
