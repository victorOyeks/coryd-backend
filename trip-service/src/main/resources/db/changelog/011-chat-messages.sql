--liquibase formatted sql

--changeset coryd:019-chat-messages
-- Trip Group Chat - one thread per route post. Membership is computed (driver, or any rider
-- with a non-terminal booking on this route), not a separate membership table. Every row for a
-- route is deleted outright the moment that route completes - see BookingService.completeRoute.
CREATE TABLE trip.chat_messages
(
    id               VARCHAR(36) PRIMARY KEY,
    custom_id        VARCHAR(20) UNIQUE,
    route_post_id    VARCHAR(36)  NOT NULL,
    sender_id        VARCHAR(36)  NOT NULL,
    type             VARCHAR(10)  NOT NULL,
    content          VARCHAR(1000) NOT NULL,
    duration_seconds INTEGER,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL
);
CREATE INDEX idx_chat_messages_route_post_id ON trip.chat_messages (route_post_id);
