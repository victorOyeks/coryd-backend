package org.coryd.notification.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.notification.dto.UnreadCountResponse;
import org.coryd.notification.entity.UserNotification;
import org.coryd.notification.repository.UserNotificationRepository;
import org.coryd.notification.security.NotificationOwnership;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Backs the bell icon: the rider/driver-facing notification feed - see UserNotification for why this is separate from the
 * delivery-audit NotificationLog. Only the user themself (or another service) may read or mark their feed - NotificationOwnership.
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final UserNotificationRepository userNotificationRepository;
    private final NotificationOwnership ownership;

    @GetMapping("/{userId}")
    public ApiResponse<List<UserNotification>> list(@PathVariable String userId,
                                                    @AuthenticationPrincipal CoRydUserDetails caller, HttpServletRequest request) {
        ownership.requireSelf(userId, caller, request);
        return ApiResponse.ok(userNotificationRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @GetMapping("/{userId}/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount(@PathVariable String userId,
                                                        @AuthenticationPrincipal CoRydUserDetails caller, HttpServletRequest request) {
        ownership.requireSelf(userId, caller, request);
        return ApiResponse.ok(new UnreadCountResponse(userNotificationRepository.countByUserIdAndReadFalse(userId)));
    }

    /** Someone else's notification answers exactly like a missing one (404), so ids can't be probed. */
    @PostMapping("/{notificationId}/read")
    @Transactional
    public ApiResponse<UserNotification> markRead(@PathVariable String notificationId,
                                                  @AuthenticationPrincipal CoRydUserDetails caller, HttpServletRequest request) {
        boolean service = ownership.isService(request);
        UserNotification notification = userNotificationRepository.findById(notificationId)
                .filter(n -> service || (caller != null && n.getUserId().equals(caller.getUserId())))
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        notification.setRead(true);
        notification.setReadAt(Instant.now());
        return ApiResponse.ok(userNotificationRepository.save(notification));
    }

    @PostMapping("/users/{userId}/read-all")
    @Transactional
    public ApiResponse<Void> markAllRead(@PathVariable String userId,
                                         @AuthenticationPrincipal CoRydUserDetails caller, HttpServletRequest request) {
        ownership.requireSelf(userId, caller, request);
        userNotificationRepository.markAllReadForUser(userId, Instant.now());
        return ApiResponse.ok("All notifications marked read", null);
    }
}
