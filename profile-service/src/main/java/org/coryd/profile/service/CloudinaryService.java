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

    private static final List<String> ALLOWED_IMAGE_AND_DOC_TYPES = List.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "application/pdf"
    );
    /** Group chat voice notes - covers the common iOS/Android recorder output formats. Cloudinary
     * files audio under resource_type "video", not "raw" or "image" - see upload() below. */
    private static final List<String> ALLOWED_AUDIO_TYPES = List.of(
            "audio/m4a", "audio/x-m4a", "audio/mp4", "audio/aac", "audio/mpeg", "audio/wav", "audio/webm", "audio/3gpp"
    );
    private static final long MAX_BYTES = 10 * 1024 * 1024; // 10 MB

    public MediaUploadResponse upload(MultipartFile file) {
        validateFile(file);

        try {
            String publicId = folder + "/" + UUID.randomUUID().toString().replace("-", "");
            String contentType = file.getContentType();
            String resourceType = "application/pdf".equals(contentType) ? "raw"
                    : ALLOWED_AUDIO_TYPES.contains(contentType == null ? null : contentType.toLowerCase()) ? "video"
                    : "image";

            @SuppressWarnings("unchecked")
            Map<String, Object> result = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "public_id", publicId,
                            "overwrite", false,
                            "resource_type", resourceType
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
        String contentType = file.getContentType() == null ? null : file.getContentType().toLowerCase();
        if (contentType == null || !(ALLOWED_IMAGE_AND_DOC_TYPES.contains(contentType) || ALLOWED_AUDIO_TYPES.contains(contentType))) {
            throw new BadRequestException("Unsupported file type - only JPEG, PNG, WebP, PDF, and common audio formats (m4a/mp4/aac/mp3/wav/webm/3gp) are accepted");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BadRequestException("File too large - maximum size is 10 MB");
        }
    }
}
