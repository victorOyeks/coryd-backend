package org.coryd.profile.repository;

import org.coryd.profile.entity.MediaUpload;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaUploadRepository extends JpaRepository<MediaUpload, String> {
}
