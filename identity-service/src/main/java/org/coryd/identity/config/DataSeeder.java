package org.coryd.identity.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.devseed.SeedFixtures;
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
 * dev/test only - seeds a full, ready-to-use fleet so the app can be exercised end to end
 * without stepping through onboarding by hand: SeedFixtures.RIDERS/DRIVERS get fixed ids here
 * so profile-service's own DataSeeder can build DriverProfile/Vehicle/DriverDocument/
 * RiderProfile rows against the exact same userId, plus one OPS_ADMIN account. Idempotent:
 * safe to run on every restart, always resets the password back to SEED_PASSWORD.
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
        SeedFixtures.RIDERS.forEach(rider -> seedUser(rider.id(), rider.phone(), rider.fullName(), UserRole.RIDER));
        SeedFixtures.DRIVERS.forEach(driver -> seedUser(driver.id(), driver.phone(), driver.fullName(), UserRole.DRIVER));
        // OPS_ADMIN can't be self-assigned via signup/roles-add (AuthService blocks it) - this
        // seeder is the only way to get one in dev/test. No other service needs this account's
        // id ahead of time, so it's left auto-generated rather than fixed.
        seedUser(null, "08000000003", "Test Ops Admin", UserRole.OPS_ADMIN);
        log.info("Seeded {} riders + {} drivers (phones {}-{} / {}-{}) and 1 ops admin (08000000003) - password: {}",
                SeedFixtures.RIDERS.size(), SeedFixtures.DRIVERS.size(),
                SeedFixtures.RIDERS.getFirst().phone(), SeedFixtures.RIDERS.getLast().phone(),
                SeedFixtures.DRIVERS.getFirst().phone(), SeedFixtures.DRIVERS.getLast().phone(),
                SEED_PASSWORD);
    }

    private void seedUser(String fixedId, String phone, String fullName, UserRole role) {
        User user = userRepository.findByPhone(phone)
                .orElseGet(() -> fixedId == null
                        ? User.builder().phone(phone).build()
                        : User.builder().id(fixedId).phone(phone).build());
        user.setFullName(fullName);
        user.getRoles().add(role);
        user.setPasswordHash(passwordEncoder.encode(SEED_PASSWORD));
        user.setPhoneVerifiedAt(Instant.now());
        user.setTermsAcceptedAt(Instant.now());
        userRepository.save(user);

        // Hibernate's @UuidGenerator only respects a pre-assigned id if it's already non-null
        // at insert time - if that assumption is ever wrong (e.g. a Hibernate upgrade changes
        // this), profile-service's DataSeeder would silently build DriverProfile/RiderProfile
        // rows against a userId that doesn't match any real account. Fail loudly instead.
        if (fixedId != null && !fixedId.equals(user.getId())) {
            throw new IllegalStateException("Seed user for phone " + phone + " got id " + user.getId()
                    + " instead of the fixed " + fixedId + " - profile-service's seeder will not line up with this account");
        }
    }
}
