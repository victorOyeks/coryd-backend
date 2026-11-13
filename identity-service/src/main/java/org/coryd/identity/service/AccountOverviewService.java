package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.AccountDeletionStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.identity.dto.AccountOverview;
import org.coryd.identity.entity.User;
import org.coryd.identity.entity.UserDevice;
import org.coryd.identity.repository.AccountDeletionRequestRepository;
import org.coryd.identity.repository.UserDeviceRepository;
import org.coryd.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** GET /internal/users/{id}/account - the account half of the ops console's driver and rider profiles. */
@Service
@RequiredArgsConstructor
public class AccountOverviewService {

    private final UserRepository userRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final AccountActionService accountActionService;
    private final AccountDeletionRequestRepository deletionRequestRepository;
    private final AccountDeletionRecorder deletionRecorder;

    @Transactional(readOnly = true)
    public AccountOverview overview(String userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        List<UserDevice> devices = userDeviceRepository.findByUserIdOrderByLastSeenAtDesc(userId);
        return new AccountOverview(
                user.getId(), user.getPhone(), user.getFirstName(), user.getLastName(), Set.copyOf(user.getRoles()),
                user.isActive() ? "ACTIVE" : "SUSPENDED",
                user.getCreatedAt(), user.getPhoneVerifiedAt(),
                devices.stream().map(UserDevice::getLastSeenAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null),
                user.getTokensValidAfter(),
                devices.stream().map(d -> new AccountOverview.Device(d.getDeviceId(), d.getDeviceName(), d.getPlatform(),
                        d.getLastSeenAt(), !d.isRevoked(), d.isBiometricEnabled())).toList(),
                user.isActive() ? null : new AccountOverview.Suspension(user.getSuspendedAt(), user.getSuspensionReason(),
                        accountActionService.lastSuspendedBy(userId)),
                accountActionService.suspensionHistory(userId),
                deletionRequestRepository.findFirstByUserIdAndStatus(userId, AccountDeletionStatus.PENDING)
                        .map(r -> new AccountOverview.PendingDeletion(r.getId(), r.getLastAttemptAt(),
                                deletionRecorder.blockers(r)))
                        .orElse(null));
    }
}
