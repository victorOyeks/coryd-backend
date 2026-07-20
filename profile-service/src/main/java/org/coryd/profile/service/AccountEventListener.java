package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.events.AccountVerifiedEvent;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Component
@RequiredArgsConstructor
public class AccountEventListener {

    private final RiderProfileRepository riderProfileRepository;
    private final DriverProfileRepository driverProfileRepository;

    @KafkaListener(topics = "coryd-account-verified", groupId = "coryd-profile-service")
    @Transactional
    public void onAccountVerified(AccountVerifiedEvent event) {
        String userId = event.getUserId();

        switch (event.getRoleAdded()) {
            case RIDER -> {
                if (riderProfileRepository.findByUserId(userId).isEmpty()) {
                    riderProfileRepository.save(RiderProfile.builder().userId(userId).build());
                    log.info("Created rider profile shell for user {}", userId);
                }
            }
            case DRIVER -> {
                if (driverProfileRepository.findByUserId(userId).isEmpty()) {
                    driverProfileRepository.save(DriverProfile.builder().userId(userId).build());
                    log.info("Created driver profile shell for user {}", userId);
                }
            }
            default -> log.debug("No profile shell needed for role {}", event.getRoleAdded());
        }
    }
}
