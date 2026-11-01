package org.coryd.profile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.exception.ExternalServiceException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.profile.client.SmileIdClient;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.LicenceReviewResponse;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Driver's Licence review screen: what Smile ID read, and the images it captured, fetched fresh for ops. */
class LicenceReviewServiceTest {

    private static final String RAW = "{\"status\":\"attention\",\"partner_params\":{\"job_id\":\"job-9\",\"user_id\":\"smile-user-9\","
            + "\"coryd_check_id\":\"coryd-dl-1\"},\"id_fields\":{\"first_name\":\"EMEKA\",\"last_name\":\"OKAFOR\","
            + "\"id_number\":\"LAG12345678\",\"date_of_birth\":\"1990-01-01\",\"expiration_date\":\"2029-05-01\"}}";

    private final VerificationCheckRepository checks = mock(VerificationCheckRepository.class);
    private final SmileIdClient smileId = mock(SmileIdClient.class);
    private final SmileIdProperties properties = new SmileIdProperties();
    private final LicenceReviewService service = new LicenceReviewService(checks, smileId, properties, new ObjectMapper());

    @BeforeEach
    void configured() {
        properties.setPartnerId("8371");
        properties.setApiKey("secret-key");
    }

    @Test
    void showsWhatSmileIdReadAndItsImagesLicenceFirst() {
        when(checks.findByDriverId("d-1")).thenReturn(List.of(
                check(VerificationCheckType.DRIVERS_LICENCE, "job-old", Instant.parse("2026-10-01T00:00:00Z"), "{}"),
                check(VerificationCheckType.DRIVERS_LICENCE, "job-9", Instant.parse("2026-10-03T00:00:00Z"), RAW),
                check(VerificationCheckType.NIN_FACE, "job-nin", Instant.parse("2026-10-04T00:00:00Z"), "{}")));
        Map<String, String> images = new LinkedHashMap<>();
        images.put("selfie_image", "https://s3/selfie.jpg");
        images.put("id_card_back", "https://s3/back.jpg");
        images.put("id_card_image", "https://s3/front.jpg");
        when(smileId.jobImages("smile-user-9", "job-9")).thenReturn(images);

        LicenceReviewResponse licence = service.licence("d-1", UserRole.OPS_ADMIN);

        assertThat(licence.smileJobId()).as("the latest licence check").isEqualTo("job-9");
        assertThat(licence.outcome()).isEqualTo("NAME_MISMATCH");
        assertThat(licence.licence()).isEqualTo(new LicenceReviewResponse.LicenceDetails("EMEKA OKAFOR", "LAG12345678", "1990-01-01", "2029-05-01"));
        assertThat(licence.images()).extracting(LicenceReviewResponse.Image::kind).containsExactly("LICENCE_FRONT", "LICENCE_BACK", "SELFIE");
        assertThat(licence.images().get(0).url()).isEqualTo("https://s3/front.jpg");
        assertThat(licence.imagesUnavailableReason()).isNull();
    }

    @Test
    void whenSmileIdCantSendTheImagesTheDetailsStillShowWithTheReason() {
        when(checks.findByDriverId("d-1")).thenReturn(List.of(check(VerificationCheckType.DRIVERS_LICENCE, "job-9", Instant.now(), RAW)));
        when(smileId.jobImages(anyString(), anyString())).thenThrow(new ExternalServiceException("Smile ID couldn't send the images right now", null));

        LicenceReviewResponse licence = service.licence("d-1", UserRole.OPS_ADMIN);

        assertThat(licence.licence().licenceNumber()).isEqualTo("LAG12345678");
        assertThat(licence.images()).isEmpty();
        assertThat(licence.imagesUnavailableReason()).contains("couldn't send the images");
    }

    @Test
    void noImagesAreAskedForWhenSmileIdIsntSetUpOrTheCheckNamesNoJob() {
        when(checks.findByDriverId("d-1")).thenReturn(List.of(check(VerificationCheckType.DRIVERS_LICENCE, null, Instant.now(), "{}")));
        assertThat(service.licence("d-1", UserRole.OPS_ADMIN).imagesUnavailableReason()).contains("no Smile ID job");

        properties.setApiKey(null);
        when(checks.findByDriverId("d-2")).thenReturn(List.of(check(VerificationCheckType.DRIVERS_LICENCE, "job-9", Instant.now(), RAW)));
        assertThat(service.licence("d-2", UserRole.OPS_ADMIN).imagesUnavailableReason()).contains("isn't configured");
        verify(smileId, never()).jobImages(anyString(), anyString());
    }

    @Test
    void aDriverWithNoLicenceCheckIsNotFoundAndOnlyOpsMayLook() {
        when(checks.findByDriverId("d-1")).thenReturn(List.of(check(VerificationCheckType.NIN_FACE, "job-nin", Instant.now(), "{}")));
        assertThatThrownBy(() -> service.licence("d-1", UserRole.OPS_ADMIN)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.licence("d-1", UserRole.DRIVER)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void smileIdsImageNamesAreSortedIntoKinds() {
        assertThat(LicenceReviewService.kind("id_card_image")).isEqualTo("LICENCE_FRONT");
        assertThat(LicenceReviewService.kind("document_image")).isEqualTo("LICENCE_FRONT");
        assertThat(LicenceReviewService.kind("id_card_back")).isEqualTo("LICENCE_BACK");
        assertThat(LicenceReviewService.kind("selfie_image")).isEqualTo("SELFIE");
        assertThat(LicenceReviewService.kind("liveness_images")).isEqualTo("SELFIE");
        assertThat(LicenceReviewService.kind("signature")).isEqualTo("OTHER");
    }

    private static VerificationCheck check(VerificationCheckType type, String jobId, Instant at, String raw) {
        VerificationCheck check = VerificationCheck.builder().driverId("d").checkType(type).subjectKey("s")
                .outcome(VerificationCheckOutcome.NAME_MISMATCH).providerReference(jobId).providerSummary("Name differs").rawResponse(raw).build();
        check.setUpdatedAt(at);
        return check;
    }
}
