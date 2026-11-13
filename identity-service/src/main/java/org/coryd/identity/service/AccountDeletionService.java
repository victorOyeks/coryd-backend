package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.UserDeletedEvent;
import org.coryd.identity.client.DeletionBlockerClient;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.BetaSignupRepository;
import org.coryd.identity.repository.OtpCodeRepository;
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
 * Deletes an account for good - asked for by its owner in the app, or by ops. The account, its logins,
 * devices, codes and waitlist entry go here; every other service is told (USER_DELETED) and erases what it
 * holds about the person, anonymising what it has to keep for accounting and for other people's trips.
 * The phone number is free afterwards, so the person can sign up again and start fresh.
 *
 * <p>Refused, with the reasons, while the person is on a trip or has money still moving (trip-service and
 * payment-service decide - including, by setting, whether a driver who owes CoRyd can be deleted, and by whom). Upcoming trips and bookings don't stop it - they're cancelled and refunded,
 * as they are for a suspension.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    public static final String SELF = "SELF";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final BetaSignupRepository betaSignupRepository;
    private final DeletionBlockerClient deletionBlockerClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AccountDeletionRecorder deletionRecorder;
    private final SessionRevocationService sessionRevocationService;
    private final org.coryd.identity.repository.AccountActionRepository accountActionRepository;

    /** The person deleting their own account. A suspended account can't - deleting must not be a way round a suspension. */
    @Transactional
    public void deleteOwn(String userId) {
        User user = find(userId);
        if (!user.isActive()) {
            throw new BadRequestException("Your account is suspended - contact CoRyd support to close it");
        }
        delete(user, SELF, false);
    }

    /** Ops deleting any account, suspended or not. */
    @Transactional
    public void deleteByOps(String userId, String opsUserId) {
        delete(find(userId), opsUserId, true);
    }

    private void delete(User user, String deletedBy, boolean byOps) {
        String userId = user.getId();
        List<String> blockers = deletionBlockerClient.blockers(userId, byOps);
        if (!blockers.isEmpty()) {
            deletionRecorder.refused(user, deletedBy, blockers);
            throw new BadRequestException("This account can't be deleted yet: " + String.join("; ", blockers));
        }
        deletionRecorder.completed(user, deletedBy);
        // Every access token still out there dies now, not at its expiry.
        sessionRevocationService.endAllSessionsOfDeleted(userId);
        accountActionRepository.deleteByUserId(userId);
        refreshTokenRepository.deleteByUserId(userId);
        userDeviceRepository.deleteByUserId(userId);
        otpCodeRepository.deleteByPhone(user.getPhone());
        betaSignupRepository.findByWhatsappNumber(user.getPhone()).ifPresent(betaSignupRepository::delete);
        userRepository.delete(user);
        log.info("Account {} deleted by {}", userId, deletedBy);

        // Only once the account is really gone do the other services start erasing.
        UserDeletedEvent event = UserDeletedEvent.builder().userId(userId).phone(user.getPhone()).deletedBy(deletedBy).deletedAt(Instant.now()).build();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kafkaTemplate.send(KafkaTopics.USER_DELETED, event);
                }
            });
        } else {
            kafkaTemplate.send(KafkaTopics.USER_DELETED, event);
        }
    }

    private User find(String userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }
}
