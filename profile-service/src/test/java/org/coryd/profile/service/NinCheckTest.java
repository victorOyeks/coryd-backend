package org.coryd.profile.service;

import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.client.PremblyClient;
import org.coryd.profile.config.PremblyProperties;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A rider's NIN + selfie through each Prembly product: what verifies them, and what leaves them for ops. */
class NinCheckTest {

    private final PremblyProperties properties = new PremblyProperties();
    private final PremblyClient client = mock(PremblyClient.class);
    private final VerificationCheckRepository checks = mock(VerificationCheckRepository.class);
    private final IdentityVerificationRepository verifications = mock(IdentityVerificationRepository.class);
    private final IdentityVerificationDecider decider = mock(IdentityVerificationDecider.class);
    private final PremblyVerificationService service = new PremblyVerificationService(properties, client, checks,
            mock(DriverProfileRepository.class), mock(VerificationAggregationService.class), verifications, decider);
    private final IdentityVerification pending = IdentityVerification.builder().userId("rider-1")
            .status(VerificationStatus.IN_REVIEW).selfiePublicId("selfie-1").build();

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(checks.findByDriverIdAndCheckTypeAndSubjectKey(anyString(), any(), anyString())).thenReturn(Optional.empty());
        when(verifications.findByUserId("rider-1")).thenReturn(Optional.of(pending));
    }

    @Test
    void ninBasicWithTheRidersNameAndALiveSelfieVerifiesThem() {
        properties.setNinCheck(PremblyProperties.NinCheck.NIN_BASIC);
        when(client.verifyNinBasic("56182742701")).thenReturn(nin("GRACE", "AMANDA", null));
        when(client.checkLiveness("https://img/s.jpg")).thenReturn(new PremblyClient.LivenessResult(true, "Liveliness Detected", 99.9, "661"));

        service.checkNin("rider-1", "56182742701", "https://img/s.jpg", "selfie-1", "Grace", "Amanda");

        assertThat(recorded()).isEqualTo(VerificationCheckOutcome.MATCH);
        verify(decider).approve(pending, IdentityVerificationDecider.PREMBLY);
        verify(client, never()).verifyNinWithFace(anyString(), anyString(), anyInt());
    }

    @Test
    void ninBasicWithAFailedLivenessCheckIsLeftForOps() {
        properties.setNinCheck(PremblyProperties.NinCheck.NIN_BASIC);
        when(client.verifyNinBasic(anyString())).thenReturn(nin("GRACE", "AMANDA", null));
        when(client.checkLiveness(anyString())).thenReturn(new PremblyClient.LivenessResult(true, "Low", 41.0, "662"));

        service.checkNin("rider-1", "56182742701", "https://img/s.jpg", "selfie-1", "Grace", "Amanda");

        assertThat(recorded()).isEqualTo(VerificationCheckOutcome.DETAILS_MISMATCH);
        verify(decider, never()).approve(any(), any());
    }

    @Test
    void someoneElsesNinIsLeftForOpsWithoutSpendingALivenessCheck() {
        properties.setNinCheck(PremblyProperties.NinCheck.NIN_BASIC);
        when(client.verifyNinBasic(anyString())).thenReturn(nin("GRACE", "AMANDA", null));

        service.checkNin("rider-1", "56182742701", "https://img/s.jpg", "selfie-1", "Tunde", "Bakare");

        assertThat(recorded()).isEqualTo(VerificationCheckOutcome.NAME_MISMATCH);
        verify(client, never()).checkLiveness(anyString());
        verify(decider, never()).approve(any(), any());
    }

    /** Prembly's own sample says confidence 1 for a match - the decision is face_data.status, not that number. */
    @Test
    void ninWithFaceIsTheDefault() {
        assertThat(new PremblyProperties().getNinCheck()).isEqualTo(PremblyProperties.NinCheck.NIN_WITH_FACE);
    }

    @Test
    void ninWithFaceTrustsPremblysMatchAndSendsOurThreshold() {
        PremblyClient.NinResult matched = new PremblyClient.NinResult(true, "ok", false, "Grace", "Amanda", true, 1.0, "r", null);
        when(client.verifyNinWithFace("56182742701", "https://img/s.jpg", 80)).thenReturn(matched);

        service.checkNin("rider-1", "56182742701", "https://img/s.jpg", "selfie-1", "Grace", "Amanda");

        assertThat(recorded()).isEqualTo(VerificationCheckOutcome.MATCH);
        verify(decider).approve(pending, IdentityVerificationDecider.PREMBLY);
        verify(client, never()).checkLiveness(anyString());
    }

    @Test
    void ninWithFaceNotMatchingIsLeftForOps() {
        when(client.verifyNinWithFace(anyString(), anyString(), anyInt()))
                .thenReturn(new PremblyClient.NinResult(true, "ok", false, "Grace", "Amanda", false, 0.2, "r", null));

        service.checkNin("rider-1", "56182742701", "https://img/s.jpg", "selfie-1", "Grace", "Amanda");

        assertThat(recorded()).isEqualTo(VerificationCheckOutcome.DETAILS_MISMATCH);
        verify(decider, never()).approve(any(), any());
    }

    @Test
    void premblyNotAnsweringIsLeftForOpsAndRetriedNextTime() {
        properties.setNinCheck(PremblyProperties.NinCheck.NIN_BASIC);
        when(client.verifyNinBasic(anyString())).thenReturn(null);

        service.checkNin("rider-1", "56182742701", "https://img/s.jpg", "selfie-1", "Grace", "Amanda");

        assertThat(recorded()).isEqualTo(VerificationCheckOutcome.PROVIDER_ERROR);
        verify(decider, never()).approve(any(), any());
    }

    private static PremblyClient.NinResult nin(String first, String last, Boolean face) {
        return new PremblyClient.NinResult(true, "ok", false, first, last, face, null, "ref", null);
    }

    private VerificationCheckOutcome recorded() {
        ArgumentCaptor<VerificationCheck> saved = ArgumentCaptor.forClass(VerificationCheck.class);
        verify(checks).save(saved.capture());
        return saved.getValue().getOutcome();
    }
}
