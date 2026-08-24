package org.coryd.profile.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.profile.dto.MediaUploadResponse;
import org.coryd.profile.service.CloudinaryService;
import org.springframework.http.MediaType;
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

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MediaUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(cloudinaryService.upload(file));
    }

    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam String publicId,
                                     @RequestParam(required = false) String resourceType) {
        cloudinaryService.delete(publicId, resourceType);
        return ApiResponse.ok("Deleted", null);
    }
}
