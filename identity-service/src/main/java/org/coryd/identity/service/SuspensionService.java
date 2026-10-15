package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.UserReinstatedEvent;
import org.coryd.common.kafka.events.UserSuspendedEvent;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.RefreshTokenRepository;
import org.coryd.identity.repository.UserRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Ops suspends an account after an incident. Every refresh token is revoked and no new ones are
 * issued (AuthService.requireActive), and trip-service is told so the user can't post or book with
 * the access token they still hold, and their upcoming trips are cancelled.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuspensionService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public void suspend(String userId, String reason, String opsUserId) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Give a reason for the suspension");
        }
        User user = find(userId);
        Instant now = Instant.now();
        user.setActive(false);
        user.setSuspendedAt(now);
        user.setSuspensionReason(reason.trim());
        userRepository.save(user);
        refreshTokenRepository.revokeAllForUser(userId);
        log.info("User {} suspended by {}: {}", userId, opsUserId, reason);

        kafkaTemplate.send(KafkaTopics.USER_SUSPENDED, UserSuspendedEvent.builder()
                .userId(userId).reason(reason.trim()).suspendedBy(opsUserId).suspendedAt(now).build());
    }

    @Transactional
    public void reinstate(String userId, String opsUserId) {
        User user = find(userId);
        user.setActive(true);
        user.setSuspendedAt(null);
        user.setSuspensionReason(null);
        userRepository.save(user);
        log.info("User {} reinstated by {}", userId, opsUserId);

        kafkaTemplate.send(KafkaTopics.USER_REINSTATED, UserReinstatedEvent.builder()
                .userId(userId).reinstatedBy(opsUserId).reinstatedAt(Instant.now()).build());
    }

    private User find(String userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
