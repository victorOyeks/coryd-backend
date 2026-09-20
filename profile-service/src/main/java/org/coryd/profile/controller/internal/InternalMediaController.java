package org.coryd.profile.controller.internal;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.profile.service.CloudinaryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only (trip-service's ProfileServiceClient, purging a completed/cancelled
 * route's Trip Group Chat voice notes) - gated by InternalApiKeyFilter via the /internal/** prefix
 * and hidden from Swagger. The user-facing DELETE /media endpoint stays JWT-authenticated and is
 * not reused here since a service call carries no user token to satisfy that. */
@RestController
@RequestMapping("/internal/media")
@RequiredArgsConstructor
public class InternalMediaController {

    private final CloudinaryService cloudinaryService;

    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam String publicId,
                                     @RequestParam(required = false) String resourceType) {
        cloudinaryService.delete(publicId, resourceType);
        return ApiResponse.ok("Deleted", null);
    }
}
