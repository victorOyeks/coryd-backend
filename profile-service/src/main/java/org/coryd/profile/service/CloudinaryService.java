package org.coryd.profile.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ExternalServiceException;
import org.coryd.profile.dto.MediaUploadResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private final Cloudinary cloudinary;

    @Value("${cloudinary.folder:coryd/uploads}")
    private String folder;

    private static final List<String> ALLOWED_TYPES = List.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "application/pdf"
    );
    private static final long MAX_BYTES = 10 * 1024 * 1024; // 10 MB

    public MediaUploadResponse upload(MultipartFile file) {
        validateFile(file);

        try {
            String publicId = folder + "/" + UUID.randomUUID().toString().replace("-", "");
            boolean isPdf = "application/pdf".equals(file.getContentType());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "public_id", publicId,
                            "overwrite", false,
                            "resource_type", isPdf ? "raw" : "image"
                    )
            );

            return new MediaUploadResponse((String) result.get("secure_url"), (String) result.get("public_id"));
        } catch (IOException e) {
            log.error("Cloudinary upload failed", e);
            throw new ExternalServiceException("Failed to upload to Cloudinary", e);
        }
    }

    /** resourceType defaults to "image" - pass "raw" explicitly to delete a PDF document uploaded above. */
    public void delete(String publicId, String resourceType) {
        if (publicId == null || publicId.isBlank()) {
            throw new BadRequestException("publicId is required");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = cloudinary.uploader().destroy(
                    publicId,
                    ObjectUtils.asMap("resource_type", resourceType == null ? "image" : resourceType)
            );

            String resultStatus = (String) result.get("result");
            if (!"ok".equals(resultStatus) && !"not found".equals(resultStatus)) {
                log.warn("Cloudinary delete returned unexpected status '{}' for publicId: {}", resultStatus, publicId);
                throw new ExternalServiceException("Failed to delete from Cloudinary: " + resultStatus);
            }
        } catch (IOException e) {
            log.error("Cloudinary delete failed for publicId: {}", publicId, e);
            throw new ExternalServiceException("Failed to delete from Cloudinary", e);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No file was uploaded");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new BadRequestException("Unsupported file type - only JPEG, PNG, WebP, and PDF are accepted");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BadRequestException("File too large - maximum size is 10 MB");
        }
    }
}
