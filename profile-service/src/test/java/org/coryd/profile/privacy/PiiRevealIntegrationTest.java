package org.coryd.profile.privacy;

import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.common.enums.PiiField;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.audit.ProfileAuditSource;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audited reveal (docs/ops-console/privacy.md) against a real Postgres: a reason is required, each reveal is recorded
 * as PII_REVEALED (never the value) and shows in the unified audit feed, a NIN is only revealed when a Smile ID result on
 * file carries it, and coryd:151 masks the numbers older provider summaries spelled out.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({PiiRevealService.class, ProfileAuditSource.class})
class PiiRevealIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final CoRydUserDetails OPS = new CoRydUserDetails("ops-1", "0800", "Ada", "Ops", UserRole.OPS_ADMIN);

    @Autowired PiiRevealService reveals;
    @Autowired ProfileAuditSource audit;
    @Autowired PiiRevealLogRepository logRepository;
    @Autowired DriverProfileRepository driverProfileRepository;
    @Autowired IdentityVerificationRepository identityVerificationRepository;
    @Autowired VerificationCheckRepository verificationCheckRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM profile.pii_reveal_log");
        jdbc.update("DELETE FROM profile.verification_checks");
        jdbc.update("DELETE FROM profile.identity_verifications");
        jdbc.update("DELETE FROM profile.driver_profiles");
        jdbc.update("DELETE FROM profile.verification_decision_log");
    }

    private void driver(String id) {
        driverProfileRepository.save(DriverProfile.builder().userId(id).firstName("Emeka").lastName("Okafor")
                .idNumber("LAG12345678").dateOfBirth(LocalDate.of(1990, 1, 1)).build());
    }

    @Test
    void revealsTheLicenceNumberAndRecordsWhoWhyAndWhichButNeverTheValue() {
        driver("d-1");

        PiiRevealService.Revealed revealed = reveals.reveal("d-1", PiiField.LICENCE_NUMBER, "  Checking against FRSC  ", OPS);

        assertThat(revealed.value()).isEqualTo("LAG12345678");
        assertThat(revealed.expiresInSeconds()).isEqualTo(60);
        assertThat(revealed.revealedAt()).isNotNull();
        List<PiiRevealLog> logged = logRepository.findByUserIdOrderByCreatedAtDesc("d-1");
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0).getActorId()).isEqualTo("ops-1");
        assertThat(logged.get(0).getActorName()).isEqualTo("Ada Ops");
        assertThat(logged.get(0).getReason()).isEqualTo("Checking against FRSC");
        assertThat(logged.get(0).getMaskedValue()).isEqualTo("•••••••5678");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profile.pii_reveal_log WHERE masked_value LIKE '%LAG%' OR reason LIKE '%LAG%'",
                Long.class)).as("the full value is never stored").isZero();
    }

    @Test
    void aReasonIsRequiredAndNothingIsRecordedWithoutOne() {
        driver("d-1");
        assertThatThrownBy(() -> reveals.reveal("d-1", PiiField.LICENCE_NUMBER, "   ", OPS))
                .isInstanceOf(BadRequestException.class).hasMessage("Say why you need to see it - it goes in the audit log");
        assertThatThrownBy(() -> reveals.reveal("d-1", PiiField.LICENCE_NUMBER, "why", new CoRydUserDetails("r-1", "0800", "R", "R", UserRole.RIDER)))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(logRepository.count()).isZero();
    }

    @Test
    void dateOfBirthComesFromTheDriverProfileOrTheNimcRecordForARider() {
        driver("d-1");
        identityVerificationRepository.save(IdentityVerification.builder().userId("r-1").status(VerificationStatus.APPROVED)
                .ninLast4("5040").verifiedDateOfBirth(LocalDate.of(1995, 6, 30)).build());

        assertThat(reveals.reveal("d-1", PiiField.DATE_OF_BIRTH, "age check", OPS).value()).isEqualTo("1990-01-01");
        PiiRevealService.Revealed rider = reveals.reveal("r-1", PiiField.DATE_OF_BIRTH, "age check", OPS);
        assertThat(rider.value()).isEqualTo("1995-06-30");
        assertThat(rider.source()).isEqualTo("NIMC_RECORD");
        assertThatThrownBy(() -> reveals.reveal("nobody", PiiField.DATE_OF_BIRTH, "age check", OPS)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aNinIsRevealedOnlyWhenASmileIdResultOnFileCarriesIt() {
        identityVerificationRepository.save(IdentityVerification.builder().userId("r-1").status(VerificationStatus.APPROVED).ninLast4("5040").build());

        assertThatThrownBy(() -> reveals.reveal("r-1", PiiField.NIN, "dispute", OPS))
                .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining("only the last four digits")
                .hasMessageContaining("•••• 5040");
        assertThat(logRepository.count()).as("nothing was shown, so nothing is recorded").isZero();

        verificationCheckRepository.save(VerificationCheck.builder().driverId("r-1").checkType(VerificationCheckType.NIN_FACE)
                .subjectKey("nin").outcome(VerificationCheckOutcome.MATCH)
                .rawResponse("{\"status\":\"clear\",\"id_fields\":{\"id_number\":\"12345675040\",\"date_of_birth\":\"1995-06-30\"}}").build());

        PiiRevealService.Revealed revealed = reveals.reveal("r-1", PiiField.NIN, "dispute", OPS);
        assertThat(revealed.value()).isEqualTo("12345675040");
        assertThat(logRepository.findByUserIdOrderByCreatedAtDesc("r-1").get(0).getMaskedValue()).isEqualTo("•••• 5040");
    }

    @Test
    void revealsShowInTheUnifiedAuditFeedAsPiiRevealed() {
        driver("d-1");
        reveals.reveal("d-1", PiiField.LICENCE_NUMBER, "Checking against FRSC", OPS);

        OpsAuditPage page = audit.page(new OpsAuditQuery(null, null, null, null, null, null, null, null, null, null, 50));
        assertThat(page.items()).hasSize(1);
        OpsAuditEntry e = page.items().get(0);
        assertThat(e.category()).isEqualTo(OpsAuditCategory.PRIVACY);
        assertThat(e.action()).isEqualTo("PII_REVEALED");
        assertThat(e.targetType()).isEqualTo("ACCOUNT");
        assertThat(e.targetId()).isEqualTo("d-1");
        assertThat(e.subject()).isEqualTo("Driver's licence number");
        assertThat(e.reason()).isEqualTo("Checking against FRSC");
        assertThat(e.actorId()).isEqualTo("ops-1");
        assertThat(e.after()).contains("LICENCE_NUMBER").doesNotContain("LAG");

        // filters: other categories / actions / target types leave reveals out
        assertThat(audit.page(new OpsAuditQuery(null, null, List.of(OpsAuditCategory.VERIFICATION), null, null, null, null, null, null, null, 50))
                .items()).isEmpty();
        assertThat(audit.page(new OpsAuditQuery(null, List.of("APPROVED"), null, null, null, null, null, null, null, null, 50)).items()).isEmpty();
        assertThat(audit.page(new OpsAuditQuery(null, List.of("PII_REVEALED"), null, List.of("ACCOUNT"), "d-1", null, null, null, null, null, 50))
                .items()).hasSize(1);
    }

    @Test
    void theMigrationMasksNumbersAndDatesOlderSummariesSpelledOut() throws Exception {
        VerificationCheck mismatch = verificationCheckRepository.save(VerificationCheck.builder().driverId("d-1")
                .checkType(VerificationCheckType.DRIVERS_LICENCE).subjectKey("licence").outcome(VerificationCheckOutcome.DETAILS_MISMATCH)
                .providerSummary("Licence number on the card is LAG12345678, driver entered LAG12345679. Smile ID flagged it").build());
        VerificationCheck genuine = verificationCheckRepository.save(VerificationCheck.builder().driverId("d-2")
                .checkType(VerificationCheckType.DRIVERS_LICENCE).subjectKey("licence").outcome(VerificationCheckOutcome.MATCH)
                .providerSummary("Genuine licence in the name of EMEKA OKAFOR, number ABC98765432, valid to 2029-05-01, live selfie matched").build());
        VerificationCheck dob = verificationCheckRepository.save(VerificationCheck.builder().driverId("d-3")
                .checkType(VerificationCheckType.NIN_LOOKUP).subjectKey("nin").outcome(VerificationCheckOutcome.DETAILS_MISMATCH)
                .providerSummary("NIMC date of birth 1990-01-01, licence 1991-02-03").build());

        String sql = Files.readString(Path.of("src/main/resources/db/changelog/034-phase7-privacy.sql"));
        String changeset = sql.substring(sql.indexOf("--changeset coryd:151"));
        for (String statement : changeset.split(";\\s*\\n")) {
            String body = statement.lines().filter(l -> !l.trim().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).trim();
            if (!body.isEmpty()) {
                jdbc.update(body);
            }
        }

        String b = "••••";
        assertThat(verificationCheckRepository.findById(mismatch.getId()).orElseThrow().getProviderSummary())
                .isEqualTo("Licence number on the card is " + b + "5678, driver entered " + b + "5679. Smile ID flagged it");
        assertThat(verificationCheckRepository.findById(genuine.getId()).orElseThrow().getProviderSummary())
                .isEqualTo("Genuine licence in the name of EMEKA OKAFOR, number " + b + "5432, valid to 2029-05-01, live selfie matched");
        assertThat(verificationCheckRepository.findById(dob.getId()).orElseThrow().getProviderSummary())
                .isEqualTo("NIMC and licence dates of birth differ (born 1990 / 1991)");
    }
}
