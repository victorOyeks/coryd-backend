package org.coryd.identity.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * dev/test only - seeds a couple of fully-onboarded accounts (password set,
 * terms accepted) so login can be tested without stepping through the whole
 * OTP -> set-password -> profile -> terms flow by hand. Idempotent: safe to
 * run on every restart, always resets the password back to SEED_PASSWORD.
 */
@Slf4j
@Component
@Profile({"dev", "test"})
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private static final String SEED_PASSWORD = "test1234";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        seedUser("08000000001", "Test Rider", UserRole.RIDER);
        seedUser("08000000002", "Test Driver", UserRole.DRIVER);
        log.info("Seeded dev/test accounts - phone: 08000000001 / 08000000002, password: {}", SEED_PASSWORD);
    }

    private void seedUser(String phone, String fullName, UserRole role) {
        User user = userRepository.findByPhone(phone)
                .orElseGet(() -> User.builder().phone(phone).build());
        user.setFullName(fullName);
        user.getRoles().add(role);
        user.setPasswordHash(passwordEncoder.encode(SEED_PASSWORD));
        user.setPhoneVerifiedAt(Instant.now());
        user.setTermsAcceptedAt(Instant.now());
        userRepository.save(user);
    }
}
