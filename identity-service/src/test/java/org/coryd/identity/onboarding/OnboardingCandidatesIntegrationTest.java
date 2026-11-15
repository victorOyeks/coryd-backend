package org.coryd.identity.onboarding;

import org.coryd.common.enums.AccountDeletionStatus;
import org.coryd.common.enums.UserRole;
import org.coryd.identity.entity.AccountDeletionRequest;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.AccountDeletionRequestRepository;
import org.coryd.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who the onboarding reminders may consider: rider and driver accounts that can sign in - never an unverified phone, no
 * password yet, a suspended or deactivated account, one waiting to be deleted, or an ops-only admin - paged by id.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import(OnboardingCandidatesService.class)
class OnboardingCandidatesIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Instant SIGNED_UP = Instant.parse("2026-11-01T09:00:00Z");

    @Autowired OnboardingCandidatesService service;
    @Autowired UserRepository users;
    @Autowired AccountDeletionRequestRepository deletions;

    @BeforeEach
    void clean() {
        deletions.deleteAll();
        users.deleteAll();
    }

    @Test
    void onlyRiderAndDriverAccountsThatCanSignInAreListedWithTheirRolesAndTermsTime() {
        User rider = save("rider", Set.of(UserRole.RIDER), u -> u.setTermsAcceptedAt(Instant.parse("2026-11-02T10:00:00Z")));
        User both = save("both", Set.of(UserRole.RIDER, UserRole.DRIVER, UserRole.OPS_ADMIN), u -> { });
        save("ops-only", Set.of(UserRole.OPS_ADMIN), u -> { });
        save("unverified", Set.of(UserRole.RIDER), u -> u.setPhoneVerifiedAt(null));
        save("no-password", Set.of(UserRole.RIDER), u -> u.setPasswordHash(null));
        save("suspended", Set.of(UserRole.DRIVER), u -> {
            u.setActive(false);
            u.setSuspendedAt(SIGNED_UP);
        });
        save("deactivated", Set.of(UserRole.DRIVER), u -> u.setActive(false));
        User leaving = save("leaving", Set.of(UserRole.RIDER), u -> { });
        deletions.save(AccountDeletionRequest.builder().userId(leaving.getId()).status(AccountDeletionStatus.PENDING).requestedBy(leaving.getId())
                .attempts(1).lastAttemptAt(SIGNED_UP).build());
        User refusedThenDone = save("done-deleting", Set.of(UserRole.RIDER), u -> { });
        deletions.save(AccountDeletionRequest.builder().userId(refusedThenDone.getId()).status(AccountDeletionStatus.COMPLETED)
                .requestedBy(refusedThenDone.getId()).attempts(1).lastAttemptAt(SIGNED_UP).build());

        List<OnboardingCandidatesService.Candidate> all = service.page(null, 50).people();
        assertThat(all).extracting(OnboardingCandidatesService.Candidate::firstName)
                .containsExactlyInAnyOrder("rider", "both", "done-deleting");
        OnboardingCandidatesService.Candidate r = all.stream().filter(c -> c.userId().equals(rider.getId())).findFirst().orElseThrow();
        assertThat(r.roles()).containsExactly("RIDER");
        assertThat(r.termsAcceptedAt()).isEqualTo(Instant.parse("2026-11-02T10:00:00Z"));
        assertThat(r.signedUpAt()).isNotNull();
        OnboardingCandidatesService.Candidate b = all.stream().filter(c -> c.userId().equals(both.getId())).findFirst().orElseThrow();
        assertThat(b.roles()).as("ops role left out").containsExactly("DRIVER", "RIDER");
        assertThat(b.termsAcceptedAt()).isNull();
    }

    @Test
    void pagesFollowEachOtherByIdWithNothingMissedOrRepeated() {
        for (int i = 0; i < 7; i++) {
            save("p" + i, Set.of(UserRole.RIDER), u -> { });
        }
        List<String> seen = new ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            OnboardingCandidatesService.Page page = service.page(after, 3);
            page.people().forEach(c -> seen.add(c.userId()));
            after = page.nextAfter();
            pages++;
        } while (after != null);
        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(7).doesNotHaveDuplicates().isSorted();
    }

    private User save(String name, Set<UserRole> roles, java.util.function.Consumer<User> tweak) {
        User user = User.builder().phone("080" + Math.abs((name + System.nanoTime()).hashCode() % 100000000)).firstName(name).lastName("Test")
                .phoneVerifiedAt(SIGNED_UP).passwordHash("hash").roles(new java.util.HashSet<>(roles)).build();
        tweak.accept(user);
        return users.save(user);
    }
}
