package org.coryd.profile.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.MediaUploadResponse;
import org.coryd.profile.service.CloudinaryService;
import org.coryd.profile.service.MediaOwnership;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/media")
@RequiredArgsConstructor
public class MediaController {

    private final CloudinaryService cloudinaryService;
    private final MediaOwnership mediaOwnership;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MediaUploadResponse> upload(@AuthenticationPrincipal CoRydUserDetails principal,
                                                   @RequestParam("file") MultipartFile file) {
        MediaUploadResponse uploaded = cloudinaryService.upload(file);
        mediaOwnership.recordUpload(uploaded.publicId(), principal.getUserId());
        return ApiResponse.ok(uploaded);
    }

    /** Only a file the caller uploaded themselves - 404 for anyone else's, as if it didn't exist. */
    @DeleteMapping
    public ApiResponse<Void> delete(@AuthenticationPrincipal CoRydUserDetails principal,
                                    @RequestParam String publicId,
                                    @RequestParam(required = false) String resourceType) {
        mediaOwnership.requireOwnUpload(publicId, principal.getUserId());
        cloudinaryService.delete(publicId, resourceType);
        return ApiResponse.ok("Deleted", null);
    }
}
