package org.coryd.trip.repository;

import org.coryd.trip.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {
    List<ChatMessage> findByRoutePostIdOrderByCreatedAtAsc(String routePostId);

    /** Wipes a route's whole conversation the moment its trip completes - see BookingService.completeRoute. */
    long deleteByRoutePostId(String routePostId);
}
