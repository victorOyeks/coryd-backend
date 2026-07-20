package org.coryd.profile.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.ApiResponse;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.repository.RiderProfileRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/riders")
@RequiredArgsConstructor
public class RiderProfileController {

    private final RiderProfileRepository riderProfileRepository;

    @GetMapping("/{userId}")
    public ApiResponse<RiderProfile> get(@PathVariable String userId) {
        return ApiResponse.ok(riderProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Rider profile not found")));
    }
}
