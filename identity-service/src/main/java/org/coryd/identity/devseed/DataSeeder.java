package org.coryd.identity.devseed;

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
 * without stepping through onboarding by hand: SeedFixtures.RIDERS/DRIVERS (shared with
 * profile-service's own DataSeeder, which resolves each account's real userId by logging in
 * as it rather than pre-agreeing on one - see IdentityAuthClient), plus one OPS_ADMIN account.
 * Idempotent: safe to run on every restart, always resets the password back to SEED_PASSWORD.
 */
@Slf4j
@Component
@Profile({"dev", "test"})
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        SeedFixtures.RIDERS.forEach(rider -> seedUser(rider.phone(), rider.firstName(), rider.lastName(), UserRole.RIDER));
        SeedFixtures.DRIVERS.forEach(driver -> seedUser(driver.phone(), driver.firstName(), driver.lastName(), UserRole.DRIVER));
        // OPS_ADMIN can't be self-assigned via signup/roles-add (AuthService blocks it) - this
        // seeder is the only way to get one in dev/test.
        seedUser("08000000003", "Test", "Ops Admin", UserRole.OPS_ADMIN);
        log.info("Seeded {} riders + {} drivers (phones {}-{} / {}-{}) and 1 ops admin (08000000003) - password: {}",
                SeedFixtures.RIDERS.size(), SeedFixtures.DRIVERS.size(),
                SeedFixtures.RIDERS.getFirst().phone(), SeedFixtures.RIDERS.getLast().phone(),
                SeedFixtures.DRIVERS.getFirst().phone(), SeedFixtures.DRIVERS.getLast().phone(),
                SeedFixtures.SEED_PASSWORD);
    }

    private void seedUser(String phone, String firstName, String lastName, UserRole role) {
        User user = userRepository.findByPhone(phone)
                .orElseGet(() -> User.builder().phone(phone).build());
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.getRoles().add(role);
        if (role == UserRole.OPS_ADMIN && user.getOpsRole() == null) {
            user.setOpsRole(org.coryd.common.enums.OpsRole.SUPER_ADMIN);
        }
        user.setPasswordHash(passwordEncoder.encode(SeedFixtures.SEED_PASSWORD));
        user.setPhoneVerifiedAt(Instant.now());
        user.setTermsAcceptedAt(Instant.now());
        userRepository.save(user);
    }
}
