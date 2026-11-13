package org.coryd.identity.devseed;

import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dev seeder's ops admin is "Seed Ops Admin" with ops-seed@coryd.local, so it can't be mistaken for the console's test
 * admin (admin@coryd.africa) - and nobody loses access: it stays a super admin, an email set by hand is kept, and the real
 * test admin is untouched. Against identity's own migrations.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@ActiveProfiles("test")
@Import({DataSeeder.class, DataSeederOpsAdminTest.Encoder.class})
class DataSeederOpsAdminTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static class Encoder {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    @Autowired DataSeeder seeder;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void testAdmin() {
        if (userRepository.findByEmail("admin@coryd.africa").isEmpty()) {
            User real = User.builder().phone("admin@coryd.africa").email("admin@coryd.africa").firstName("Test").lastName("Ops Admin")
                    .opsRole(OpsRole.SUPER_ADMIN).phoneVerifiedAt(Instant.now()).termsAcceptedAt(Instant.now()).build();
            real.getRoles().add(UserRole.OPS_ADMIN);
            userRepository.save(real);
        }
    }

    @Test
    void theSeededOpsAdminIsNamedApartAndKeepsItsAccess() throws Exception {
        seeder.run(null);
        seeder.run(null);

        User seeded = userRepository.findByPhone(DataSeeder.OPS_ADMIN_PHONE).orElseThrow();
        assertThat(seeded.getFirstName() + " " + seeded.getLastName()).isEqualTo("Seed Ops Admin");
        assertThat(seeded.getEmail()).isEqualTo("ops-seed@coryd.local");
        assertThat(seeded.getRoles()).contains(UserRole.OPS_ADMIN);
        assertThat(seeded.getOpsRole()).isEqualTo(OpsRole.SUPER_ADMIN);

        User real = userRepository.findByEmail("admin@coryd.africa").orElseThrow();
        assertThat(real.getFirstName() + " " + real.getLastName()).as("the console's test admin is untouched").isEqualTo("Test Ops Admin");
        assertThat(real.getRoles()).contains(UserRole.OPS_ADMIN);
    }

    @Test
    void anEmailSetByHandIsKept() throws Exception {
        seeder.run(null);
        User seeded = userRepository.findByPhone(DataSeeder.OPS_ADMIN_PHONE).orElseThrow();
        seeded.setEmail("someone@coryd.africa");
        userRepository.save(seeded);

        seeder.run(null);

        assertThat(userRepository.findByPhone(DataSeeder.OPS_ADMIN_PHONE).orElseThrow().getEmail()).isEqualTo("someone@coryd.africa");
    }
}
