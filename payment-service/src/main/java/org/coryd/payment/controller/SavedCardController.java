package org.coryd.payment.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.payment.dto.SavedCardResponse;
import org.coryd.payment.service.SavedCardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Always the signed-in rider's own cards. */
@RestController
@RequestMapping("/cards")
@RequiredArgsConstructor
public class SavedCardController {

    private final SavedCardService savedCardService;

    @GetMapping
    public ApiResponse<List<SavedCardResponse>> list(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(savedCardService.list(principal.getUserId()));
    }

    @DeleteMapping("/{cardId}")
    public ApiResponse<Void> remove(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String cardId) {
        savedCardService.remove(principal.getUserId(), cardId);
        return ApiResponse.ok("Card removed", null);
    }
}
