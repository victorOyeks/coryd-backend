package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.AccountActionType;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.UserReinstatedEvent;
import org.coryd.common.kafka.events.UserSuspendedEvent;
import org.coryd.common.kafka.events.UserTokensRevokedEvent;
import org.coryd.identity.dto.RecordAccountActionRequest;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.UserRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Ops suspends an account after an incident, lifts the suspension, or signs the account out everywhere - each with a
 * reason, recorded in the account history (AccountActionService).
 *
 * <p>Suspending and signing out end every session now (SessionRevocationService): every service refuses the access tokens
 * already issued within seconds, and no new ones are issued while suspended (AuthService.requireActive). trip-service is
 * also told (USER_SUSPENDED) so upcoming trips and seats are cancelled and refunded. Reinstating lets the person sign in
 * again; the sessions that were ended stay ended.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuspensionService {

    private final UserRepository userRepository;
    private final SessionRevocationService sessionRevocationService;
    private final AccountActionService accountActionService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public void suspend(String userId, String reason, String opsUserId, String opsUserName) {
        String why = requireReason(reason, "Give a reason for the suspension");
        User user = find(userId);
        if (!user.isActive()) {
            throw new BadRequestException("This account is already suspended");
        }
        Instant now = Instant.now();
        user.setActive(false);
        user.setSuspendedAt(now);
        user.setSuspensionReason(why);
        sessionRevocationService.endAllSessions(user, UserTokensRevokedEvent.SUSPENDED);
        accountActionService.record(userId, AccountActionType.SUSPENDED, opsUserId, opsUserName, why,
                List.of(new RecordAccountActionRequest.Change("status", "ACTIVE", "SUSPENDED")));
        log.info("User {} suspended by {}: {}", userId, opsUserId, why);

        kafkaTemplate.send(KafkaTopics.USER_SUSPENDED, UserSuspendedEvent.builder()
                .userId(userId).reason(why).suspendedBy(opsUserId).suspendedAt(now).build());
    }

    /** The reason is required and kept in the history next to the suspension it ends. */
    @Transactional
    public void reinstate(String userId, String reason, String opsUserId, String opsUserName) {
        String why = requireReason(reason, "Give a reason for reinstating this account");
        User user = find(userId);
        if (user.isActive()) {
            throw new BadRequestException("This account isn't suspended");
        }
        String suspendedFor = user.getSuspensionReason();
        user.setActive(true);
        user.setSuspendedAt(null);
        user.setSuspensionReason(null);
        userRepository.save(user);
        sessionRevocationService.publishState(user, UserTokensRevokedEvent.REINSTATED);
        accountActionService.record(userId, AccountActionType.REINSTATED, opsUserId, opsUserName, why,
                List.of(new RecordAccountActionRequest.Change("status", "SUSPENDED", "ACTIVE"),
                        new RecordAccountActionRequest.Change("suspensionReason", suspendedFor, null)));
        log.info("User {} reinstated by {}: {}", userId, opsUserId, why);

        kafkaTemplate.send(KafkaTopics.USER_REINSTATED, UserReinstatedEvent.builder()
                .userId(userId).reinstatedBy(opsUserId).reinstatedAt(Instant.now()).build());
    }

    /** Every session on every device ends now; the person signs in again (they're told - notification-service). */
    @Transactional
    public void signOutEverywhere(String userId, String reason, String opsUserId, String opsUserName) {
        String why = requireReason(reason, "Give a reason for signing this account out");
        User user = find(userId);
        sessionRevocationService.endAllSessions(user, UserTokensRevokedEvent.SIGNED_OUT_EVERYWHERE);
        accountActionService.record(userId, AccountActionType.SIGNED_OUT_EVERYWHERE, opsUserId, opsUserName, why, null);
        log.info("User {} signed out everywhere by {}: {}", userId, opsUserId, why);
    }

    private static String requireReason(String reason, String message) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(message);
        }
        return reason.trim();
    }

    private User find(String userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
