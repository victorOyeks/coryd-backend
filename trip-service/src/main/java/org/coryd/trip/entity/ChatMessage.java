package org.coryd.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.ChatMessageType;

/** One message in a route's Trip Group Chat - every message for a route is deleted the moment
 * that route completes (see BookingService.completeRoute), so nothing here outlives the trip
 * it belongs to. */
@Entity
@Table(name = "chat_messages", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage extends BaseEntity {

    @Column(nullable = false)
    private String routePostId;

    @Column(nullable = false)
    private String senderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChatMessageType type;

    /** The text body for a TEXT message, or the uploaded audio URL for a VOICE one. */
    @Column(nullable = false, length = 1000)
    private String content;

    /** Only set for VOICE messages. */
    private Integer durationSeconds;

    /** Cloudinary public_id for a VOICE message's audio, from the earlier /media/upload response -
     * lets the route's media be purged from Cloudinary itself when its chat is deleted, not just this row. */
    private String mediaPublicId;
}
