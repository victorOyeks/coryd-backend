package org.coryd.trip.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.entity.UserBlock;
import org.coryd.trip.service.UserBlockService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** "Block this driver" / "Block this rider" - always the caller's own list. */
@RestController
@RequestMapping("/blocks")
@RequiredArgsConstructor
public class BlockController {

    private final UserBlockService userBlockService;

    @GetMapping
    public ApiResponse<List<UserBlock>> mine(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(userBlockService.blockedBy(principal.getUserId()));
    }

    @PostMapping("/{userId}")
    public ApiResponse<Void> block(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String userId) {
        userBlockService.block(principal.getUserId(), userId);
        return ApiResponse.ok("Blocked", null);
    }

    @DeleteMapping("/{userId}")
    public ApiResponse<Void> unblock(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String userId) {
        userBlockService.unblock(principal.getUserId(), userId);
        return ApiResponse.ok("Unblocked", null);
    }
}
