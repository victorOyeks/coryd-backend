package org.coryd.common.devseed;

import java.util.List;

/**
 * Fixed phone/id pairs shared between identity-service's DataSeeder (creates the Users) and
 * profile-service's DataSeeder (creates the matching DriverProfile/Vehicle/DriverDocument/
 * RiderProfile rows), dev/test only. The id has to be pre-agreed rather than looked up at
 * runtime - each service seeds its own database independently at its own startup, with no way
 * to ask another service "what id did you just generate for this phone." Hibernate's
 * {@code @UuidGenerator} uses a pre-assigned, non-null id as-is instead of generating a new
 * one, which is what makes sharing a fixed id across two separately-seeded services work.
 */
public final class SeedFixtures {

    private SeedFixtures() {
    }

    public record SeedPerson(String id, String phone, String fullName) {
    }

    public static final List<SeedPerson> DRIVERS = List.of(
            new SeedPerson("00000000-0000-0000-0000-000000000201", "08000000201", "Test Driver 1"),
            new SeedPerson("00000000-0000-0000-0000-000000000202", "08000000202", "Test Driver 2"),
            new SeedPerson("00000000-0000-0000-0000-000000000203", "08000000203", "Test Driver 3"),
            new SeedPerson("00000000-0000-0000-0000-000000000204", "08000000204", "Test Driver 4"),
            new SeedPerson("00000000-0000-0000-0000-000000000205", "08000000205", "Test Driver 5"),
            new SeedPerson("00000000-0000-0000-0000-000000000206", "08000000206", "Test Driver 6"),
            new SeedPerson("00000000-0000-0000-0000-000000000207", "08000000207", "Test Driver 7"),
            new SeedPerson("00000000-0000-0000-0000-000000000208", "08000000208", "Test Driver 8"),
            new SeedPerson("00000000-0000-0000-0000-000000000209", "08000000209", "Test Driver 9"),
            new SeedPerson("00000000-0000-0000-0000-000000000210", "08000000210", "Test Driver 10")
    );

    public static final List<SeedPerson> RIDERS = List.of(
            new SeedPerson("00000000-0000-0000-0000-000000000101", "08000000101", "Test Rider 1"),
            new SeedPerson("00000000-0000-0000-0000-000000000102", "08000000102", "Test Rider 2"),
            new SeedPerson("00000000-0000-0000-0000-000000000103", "08000000103", "Test Rider 3")
    );
}
