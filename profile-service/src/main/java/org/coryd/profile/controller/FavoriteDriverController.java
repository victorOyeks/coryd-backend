package org.coryd.profile.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.entity.FavoriteDriver;
import org.coryd.profile.service.FavoriteDriverService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/riders")
@RequiredArgsConstructor
public class FavoriteDriverController {

    private final FavoriteDriverService favoriteDriverService;

    @GetMapping("/me/favorite-drivers")
    public ApiResponse<List<FavoriteDriver>> list(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(favoriteDriverService.list(principal.getUserId()));
    }

    @PostMapping("/me/favorite-drivers/{driverId}")
    public ApiResponse<FavoriteDriver> add(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String driverId) {
        return ApiResponse.ok(favoriteDriverService.add(principal.getUserId(), driverId));
    }

    @DeleteMapping("/me/favorite-drivers/{driverId}")
    public ApiResponse<Void> remove(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String driverId) {
        favoriteDriverService.remove(principal.getUserId(), driverId);
        return ApiResponse.ok("Removed from favourites", null);
    }
}
