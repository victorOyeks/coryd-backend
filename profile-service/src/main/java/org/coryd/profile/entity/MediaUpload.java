package org.coryd.profile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One file uploaded through POST /media/upload, and who uploaded it. */
@Entity
@Table(name = "media_uploads", schema = "profile")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaUpload {

    @Id
    @Column(length = 255)
    private String publicId;

    @Column(nullable = false, length = 36)
    private String userId;

    @Column(nullable = false)
    private Instant uploadedAt;
}
