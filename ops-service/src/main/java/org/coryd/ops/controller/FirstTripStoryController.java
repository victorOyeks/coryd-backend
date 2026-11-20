package org.coryd.ops.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.ops.dto.FirstTripStoryItem;
import org.coryd.ops.service.FirstTripStoryOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Set;

/** First trip stories - read only. Shared experiences only (skips aren't stories), newest first. */
@RestController
@RequestMapping("/first-trip-stories")
@RequiredArgsConstructor
public class FirstTripStoryController {

    private static final Set<String> ROLES = Set.of("DRIVER", "RIDER");

    private final FirstTripStoryOpsService service;

    @GetMapping
    public ApiResponse<PagedResponse<FirstTripStoryItem>> list(@RequestParam(required = false) String role,
                                                               @RequestParam(required = false) Boolean consent,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        String r = role == null || role.isBlank() ? null : role.trim().toUpperCase(Locale.ROOT);
        if (r != null && !ROLES.contains(r)) {
            throw new BadRequestException("role must be DRIVER or RIDER");
        }
        return ApiResponse.ok(service.list(r, consent, Math.max(page, 0), Math.max(1, Math.min(size, 50))));
    }
}
