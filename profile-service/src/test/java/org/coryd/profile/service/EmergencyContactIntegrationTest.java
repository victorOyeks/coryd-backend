package org.coryd.profile.service;

import org.coryd.common.exception.BadRequestException;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.dto.UpdateRiderProfileRequest;
import org.coryd.profile.entity.EmergencyContact;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.EmergencyContactRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Emergency contacts and the profile PUTs against a throwaway Postgres container built from
 * profile-service's own Liquibase migrations - never a shared or real database.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({EmergencyContactService.class, RiderProfileService.class, DriverProfileService.class})
class EmergencyContactIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String USER = "user-1";

    @Autowired EmergencyContactService emergencyContactService;
    @Autowired RiderProfileService riderProfileService;
    @Autowired DriverProfileService driverProfileService;
    @Autowired EmergencyContactRepository emergencyContactRepository;
    @Autowired RiderProfileRepository riderProfileRepository;
    @Autowired DriverProfileRepository driverProfileRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;

    @MockBean IdentityServiceClient identityServiceClient;
    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean PremblyVerificationService premblyVerificationService;

    @BeforeEach
    void cleanSlate() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL())
                    .as("tests must only ever run against the Testcontainers database")
                    .isEqualTo(postgres.getJdbcUrl());
        }
        emergencyContactRepository.deleteAll();
        riderProfileRepository.deleteAll();
        driverProfileRepository.deleteAll();
    }

    @Test
    void contactSavedOnTheRiderProfileIsTheOneSosReads() {
        riderProfileService.updateMine(USER, rider("Ada", "https://img/ada.jpg", "Mum", "08030000000"));

        assertThat(emergencyContactService.getContacts(USER)).extracting(EmergencyContact::getPhone).containsExactly("08030000000");
        var profile = riderProfileService.getMine(USER);
        assertThat(profile.emergencyContactName()).isEqualTo("Mum");
        assertThat(profile.emergencyContactPhone()).isEqualTo("08030000000");
    }

    @Test
    void editingTheContactUpdatesItInPlace() {
        riderProfileService.updateMine(USER, rider("Ada", null, "Mum", "08030000000"));
        riderProfileService.updateMine(USER, rider("Ada", null, "Dad", "08040000000"));

        assertThat(emergencyContactService.getContacts(USER)).singleElement()
                .satisfies(c -> {
                    assertThat(c.getName()).isEqualTo("Dad");
                    assertThat(c.isPrimary()).isTrue();
                });
    }

    @Test
    void olderAppSendingOnlyTheNameKeepsPhotoEmailAndContact() {
        riderProfileService.updateMine(USER, new UpdateRiderProfileRequest("Ada", "Obi", "ada@example.com",
                "https://img/ada.jpg", "Mum", "08030000000"));

        riderProfileService.updateMine(USER, new UpdateRiderProfileRequest("Adaeze", null, null, null, null, null));

        var profile = riderProfileService.getMine(USER);
        assertThat(profile.firstName()).isEqualTo("Adaeze");
        assertThat(profile.lastName()).isEqualTo("Obi");
        assertThat(profile.email()).isEqualTo("ada@example.com");
        assertThat(profile.photoUrl()).isEqualTo("https://img/ada.jpg");
        assertThat(profile.emergencyContactPhone()).isEqualTo("08030000000");
    }

    @Test
    void halfAContactIsRejected() {
        assertThatThrownBy(() -> riderProfileService.updateMine(USER, rider("Ada", null, "Mum", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("both a name and a phone number");
    }

    @Test
    void driverProfileStoresAndReturnsTheContactWithoutCreatingARiderProfile() {
        driverProfileService.updateMine(USER, driver("https://img/tunde.jpg", "Wife", "08050000000"));

        var profile = driverProfileService.getMine(USER);
        assertThat(profile.emergencyContactName()).isEqualTo("Wife");
        assertThat(profile.emergencyContactPhone()).isEqualTo("08050000000");
        assertThat(riderProfileRepository.findByUserId(USER)).isEmpty();
        assertThat(emergencyContactService.getContacts(USER)).hasSize(1);
    }

    @Test
    void driverEditMissingPhotoAndContactKeepsThem() {
        driverProfileService.updateMine(USER, driver("https://img/tunde.jpg", "Wife", "08050000000"));

        driverProfileService.updateMine(USER, driver(null, null, null));

        var profile = driverProfileService.getMine(USER);
        assertThat(profile.photoUrl()).isEqualTo("https://img/tunde.jpg");
        assertThat(profile.emergencyContactPhone()).isEqualTo("08050000000");
    }

    @Test
    void dualRoleUserHasOneContactAcrossBothProfiles() {
        riderProfileService.updateMine(USER, rider("Tunde", null, "Wife", "08050000000"));

        driverProfileService.updateMine(USER, driver(null, "Brother", "08060000000"));

        assertThat(riderProfileService.getMine(USER).emergencyContactName()).isEqualTo("Brother");
        assertThat(driverProfileService.getMine(USER).emergencyContactName()).isEqualTo("Brother");
        assertThat(emergencyContactService.getContacts(USER)).hasSize(1);
        assertThat(riderProfileRepository.findByUserId(USER).orElseThrow().getEmergencyContactName())
                .as("legacy flat field kept in step").isEqualTo("Brother");
    }

    @Test
    void backfillCopiesProfileFormContactsIntoTheListOnce() throws Exception {
        jdbcTemplate.update("insert into profile.rider_profiles (id, user_id, first_name, emergency_contact_name, emergency_contact_phone, created_at, updated_at) "
                + "values ('p-1', 'legacy-rider', 'Ngozi', 'Sister', '08070000000', now(), now())");
        jdbcTemplate.update("insert into profile.rider_profiles (id, user_id, first_name, created_at, updated_at) "
                + "values ('p-2', 'no-contact', 'Emeka', now(), now())");
        String sql = new String(new ClassPathResource("db/changelog/019-backfill-emergency-contacts.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        String insert = sql.substring(sql.indexOf("INSERT INTO"));

        jdbcTemplate.execute(insert);
        jdbcTemplate.execute(insert);

        assertThat(emergencyContactService.getContacts("legacy-rider")).singleElement()
                .satisfies(c -> {
                    assertThat(c.getPhone()).isEqualTo("08070000000");
                    assertThat(c.isPrimary()).isTrue();
                });
        assertThat(emergencyContactService.getContacts("no-contact")).isEmpty();
    }

    private static UpdateRiderProfileRequest rider(String firstName, String photoUrl, String contactName, String contactPhone) {
        return new UpdateRiderProfileRequest(firstName, null, null, photoUrl, contactName, contactPhone);
    }

    private static UpdateDriverProfileRequest driver(String photoUrl, String contactName, String contactPhone) {
        return new UpdateDriverProfileRequest("Tunde", "Bakare", photoUrl, null, LocalDate.of(1990, 1, 1),
                null, null, "LAG12345678", contactName, contactPhone);
    }
}
