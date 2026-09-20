package org.coryd.common.devseed;

import org.coryd.common.util.Names;

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

    public record SeedPerson(String phone, String firstName, String lastName) {

        public String fullName() {
            return Names.join(firstName, lastName);
        }
    }

    public static final List<SeedPerson> DRIVERS = List.of(
            new SeedPerson("08000000201", "Kunle", "Adeyemi"),
            new SeedPerson("08000000202", "Chidi", "Okafor"),
            new SeedPerson("08000000203", "Bola", "Ogunleye"),
            new SeedPerson("08000000204", "Emeka", "Nwosu"),
            new SeedPerson("08000000205", "Tunde", "Bakare"),
            new SeedPerson("08000000206", "Ifeoma", "Chukwu"),
            new SeedPerson("08000000207", "Segun", "Alabi"),
            new SeedPerson("08000000208", "Ngozi", "Eze"),
            new SeedPerson("08000000209", "Yusuf", "Abdullahi"),
            new SeedPerson("08000000210", "Femi", "Adebayo")
    );

    public static final List<SeedPerson> RIDERS = List.of(
            new SeedPerson("08000000101", "Amaka", "Obi"),
            new SeedPerson("08000000102", "Tolu", "Fashola"),
            new SeedPerson("08000000103", "Chinwe", "Anya"),
            new SeedPerson("08000000104", "Bisi", "Adekunle"),
            new SeedPerson("08000000105", "Musa", "Ibrahim")
    );
}
