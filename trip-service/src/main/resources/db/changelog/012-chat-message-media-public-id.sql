--liquibase formatted sql

--changeset coryd:020-chat-message-media-public-id
-- Cloudinary public_id for a VOICE message's audio, so the asset itself can be purged from
-- Cloudinary (not just this row) once the route's chat is deleted.
ALTER TABLE trip.chat_messages ADD COLUMN media_public_id VARCHAR(255);
