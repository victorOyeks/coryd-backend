package org.coryd.common.devseed;

import java.util.List;

/**
 * Phone/name pairs shared between identity-service's DataSeeder (creates the Users) and
 * profile-service's DataSeeder (creates the matching DriverProfile/Vehicle/DriverDocument/
 * RiderProfile rows), dev/test only. Deliberately does NOT carry a fixed userId - each
 * service seeds its own database independently at its own startup, and a pre-assigned id
 * doesn't survive Hibernate's {@code @UuidGenerator} (it always generates a fresh UUID on
 * insert, regardless of what the entity was built with). profile-service's DataSeeder
 * resolves the real, identity-service-generated userId by logging in as the seed account
 * instead (see IdentityAuthClient) - SEED_PASSWORD is what it logs in with.
 */
public final class SeedFixtures {

    private SeedFixtures() {
    }

    public static final String SEED_PASSWORD = "test1234";

    public record SeedPerson(String phone, String fullName) {
    }

    public static final List<SeedPerson> DRIVERS = List.of(
            new SeedPerson("08000000201", "Test Driver 1"),
            new SeedPerson("08000000202", "Test Driver 2"),
            new SeedPerson("08000000203", "Test Driver 3"),
            new SeedPerson("08000000204", "Test Driver 4"),
            new SeedPerson("08000000205", "Test Driver 5"),
            new SeedPerson("08000000206", "Test Driver 6"),
            new SeedPerson("08000000207", "Test Driver 7"),
            new SeedPerson("08000000208", "Test Driver 8"),
            new SeedPerson("08000000209", "Test Driver 9"),
            new SeedPerson("08000000210", "Test Driver 10")
    );

    public static final List<SeedPerson> RIDERS = List.of(
            new SeedPerson("08000000101", "Test Rider 1"),
            new SeedPerson("08000000102", "Test Rider 2"),
            new SeedPerson("08000000103", "Test Rider 3")
    );
}
