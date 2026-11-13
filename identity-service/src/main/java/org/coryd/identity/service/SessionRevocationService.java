package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.UserTokensRevokedEvent;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.coryd.common.security.revocation.TokenRevocationSource;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.RefreshTokenRepository;
import org.coryd.identity.repository.UserDeviceRepository;
import org.coryd.identity.repository.UserRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

/**
 * Ends a user's sessions everywhere, now - not when their access token expires. Every refresh token is revoked and every
 * device signed out here; tokens_valid_after moves to now, and once the change commits it goes on the compacted
 * user-tokens-revoked topic, which every service's JwtFilter follows (and reads back in full at startup, with this
 * service's snapshot as the fallback). identity-service applies it to its own registry straight away.
 *
 * <p>Also the snapshot source inside identity-service (other services fetch it over HTTP).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionRevocationService implements TokenRevocationSource {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final TokenRevocationRegistry registry;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** Sets the cut-off, revokes every refresh token and device, and publishes once committed. Caller holds the transaction. */
    @Transactional
    public void endAllSessions(User user, String reason) {
        Instant now = Instant.now();
        user.setTokensValidAfter(now);
        userRepository.save(user);
        refreshTokenRepository.revokeAllForUser(user.getId());
        userDeviceRepository.revokeAllForUser(user.getId());
        publish(event(user.getId(), now, !user.isActive(), reason, now));
    }

    /**
     * Access tokens issued until now stop working everywhere, but refresh tokens and devices are left alone - the next
     * refresh hands out a token that says what changed (an ops admin's new ops role).
     */
    @Transactional
    public void cutOffAccessTokens(User user, String reason) {
        Instant now = Instant.now();
        user.setTokensValidAfter(now);
        userRepository.save(user);
        publish(event(user.getId(), now, !user.isActive(), reason, now));
    }

    /** The account's suspension changed without a new cut-off (a reinstate): every service learns it's no longer suspended. */
    @Transactional
    public void publishState(User user, String reason) {
        publish(event(user.getId(), user.getTokensValidAfter(), !user.isActive(), reason, Instant.now()));
    }

    /** The account is being deleted - its tokens die with it. The topic keeps this; the snapshot can't (the row is gone). */
    @Transactional
    public void endAllSessionsOfDeleted(String userId) {
        Instant now = Instant.now();
        publish(event(userId, now, false, UserTokensRevokedEvent.DELETED, now));
    }

    /** Everyone with a cut-off or a suspension - GET /internal/users/token-revocations, and this service's own bootstrap. */
    @Override
    @Transactional(readOnly = true)
    public List<UserTokensRevokedEvent> fetchAll() {
        return userRepository.findWithRevokedTokens().stream()
                .map(u -> event(u.getId(), u.getTokensValidAfter(), !u.isActive(), u.isActive() ? null : UserTokensRevokedEvent.SUSPENDED,
                        u.getUpdatedAt()))
                .toList();
    }

    private static UserTokensRevokedEvent event(String userId, Instant validAfter, boolean suspended, String reason, Instant at) {
        return UserTokensRevokedEvent.builder().userId(userId).tokensValidAfter(validAfter).suspended(suspended)
                .reason(reason).stateAt(at).build();
    }

    private void publish(UserTokensRevokedEvent event) {
        Runnable send = () -> {
            registry.apply(event);
            // Keyed by user: the topic is compacted, so the latest state per user is what's kept.
            kafkaTemplate.send(KafkaTopics.USER_TOKENS_REVOKED, event.getUserId(), event);
            log.info("Sessions of {} updated everywhere ({}): valid after {}, suspended {}", event.getUserId(), event.getReason(),
                    event.getTokensValidAfter(), event.isSuspended());
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
