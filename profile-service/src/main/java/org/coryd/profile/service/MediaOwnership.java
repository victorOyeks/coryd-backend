package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.profile.entity.MediaUpload;
import org.coryd.profile.repository.MediaUploadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Who uploaded which file. Cloudinary publicIds come back to the apps and are visible in image URLs, so without this
 * anyone could delete another person's licence or selfie through DELETE /media, or submit someone else's file as their
 * own - and have it deleted for them when they later replace it (ReplacedFiles).
 */
@Service
@RequiredArgsConstructor
public class MediaOwnership {

    private final MediaUploadRepository mediaUploadRepository;

    @Transactional
    public void recordUpload(String publicId, String userId) {
        mediaUploadRepository.save(MediaUpload.builder().publicId(publicId).userId(userId).uploadedAt(Instant.now()).build());
    }

    /** DELETE /media: only a file the caller uploaded. Files from before uploads were recorded can't be deleted this way. */
    @Transactional(readOnly = true)
    public void requireOwnUpload(String publicId, String userId) {
        boolean own = publicId != null && mediaUploadRepository.findById(publicId)
                .map(upload -> upload.getUserId().equals(userId))
                .orElse(false);
        if (!own) {
            throw new ResourceNotFoundException("File not found");
        }
    }

    /**
     * A file submitted on a profile, vehicle or document: refused when it is on record as someone else's upload. A file
     * with no record (uploaded before recording began) still passes, so nobody mid-onboarding is stuck.
     */
    @Transactional(readOnly = true)
    public void refuseOthersUpload(String publicId, String userId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        mediaUploadRepository.findById(publicId)
                .filter(upload -> !upload.getUserId().equals(userId))
                .ifPresent(upload -> {
                    throw new BadRequestException("That file wasn't uploaded from your account - upload it again");
                });
    }
}
