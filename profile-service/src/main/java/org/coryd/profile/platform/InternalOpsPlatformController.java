package org.coryd.profile.platform;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Service-to-service only (/internal/** is key-gated): broadcast audiences for notification-service (called from its
 * sender job, with a system token), the verification funnel and pipeline for ops-service's dashboard and reports.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class InternalOpsPlatformController {

    private final AudienceService audienceService;
    private final VerificationFunnelService funnelService;

    @GetMapping("/internal/ops/audience")
    public ApiResponse<AudienceService.Page> audience(@RequestParam AudienceService.Type type,
                                                      @RequestParam(required = false) Integer activeDays,
                                                      @RequestParam(required = false) List<String> ids,
                                                      @RequestParam(required = false) List<String> refs,
                                                      @RequestParam(required = false) String after,
                                                      @RequestParam(defaultValue = "500") int size) {
        return ApiResponse.ok(audienceService.page(type, activeDays, ids, refs, after, size));
    }

    @GetMapping("/internal/ops/audience/count")
    public ApiResponse<AudienceService.Count> audienceCount(@RequestParam AudienceService.Type type,
                                                           @RequestParam(required = false) Integer activeDays,
                                                           @RequestParam(required = false) List<String> ids,
                                                           @RequestParam(required = false) List<String> refs) {
        return ApiResponse.ok(audienceService.count(type, activeDays, ids, refs));
    }

    @GetMapping("/internal/ops/verification-funnel")
    public ApiResponse<VerificationFunnelService.Funnel> funnel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(funnelService.funnel(from, to));
    }

    @GetMapping("/internal/ops/pipeline")
    public ApiResponse<VerificationFunnelService.Pipeline> pipeline() {
        return ApiResponse.ok(funnelService.pipeline());
    }
}
