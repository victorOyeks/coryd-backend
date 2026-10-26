package org.coryd.profile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerifiedIdentityServiceTest {

    private final IdentityVerificationRepository verifications = mock(IdentityVerificationRepository.class);
    private final VerifiedIdentityService service = new VerifiedIdentityService(verifications, mock(VerificationCheckRepository.class),
            new SmileIdProperties(), new ObjectMapper());

    @Test
    void nimcCapitalsBecomeProperCase() {
        assertThat(VerifiedIdentityService.properCase("VICTOR")).isEqualTo("Victor");
        assertThat(VerifiedIdentityService.properCase(" ADE-JOHNSON ")).isEqualTo("Ade-Johnson");
        assertThat(VerifiedIdentityService.properCase("O'BRIEN")).isEqualTo("O'Brien");
        assertThat(VerifiedIdentityService.properCase("MARY  JANE")).isEqualTo("Mary Jane");
        assertThat(VerifiedIdentityService.properCase(" ")).isNull();
    }

    @Test
    void onlyAPlainDateOfBirthIsKept() {
        assertThat(VerifiedIdentityService.date("1995-06-17")).isEqualTo(LocalDate.of(1995, 6, 17));
        assertThat(VerifiedIdentityService.date("17/06/1995")).isNull();
        assertThat(VerifiedIdentityService.date(null)).isNull();
    }

    @Test
    void theNameIsLockedOnlyOnceTheNinCheckIsApproved() {
        when(verifications.findByUserId("approved")).thenReturn(Optional.of(IdentityVerification.builder().userId("approved")
                .status(VerificationStatus.APPROVED).build()));
        when(verifications.findByUserId("in-review")).thenReturn(Optional.of(IdentityVerification.builder().userId("in-review")
                .status(VerificationStatus.IN_REVIEW).build()));

        assertThat(service.nameVerified("approved")).isTrue();
        assertThat(service.nameVerified("in-review")).isFalse();
        assertThat(service.nameVerified("never-checked")).isFalse();
    }
}
