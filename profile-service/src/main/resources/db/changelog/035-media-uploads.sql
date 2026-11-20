--liquibase formatted sql

--changeset coryd:183-profile-media-uploads
-- Who uploaded each file through POST /media/upload. DELETE /media used to remove any publicId for any signed-in user;
-- it now removes only the caller's own uploads, and a file someone else uploaded can't be submitted as yours.
CREATE TABLE IF NOT EXISTS profile.media_uploads
(
    public_id   VARCHAR(255) PRIMARY KEY,
    user_id     VARCHAR(36)  NOT NULL,
    uploaded_at TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_media_uploads_user ON profile.media_uploads (user_id);
