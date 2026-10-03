package org.coryd.profile.devseed;

import org.coryd.common.devseed.SeedFixtures;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.client.IdentityAuthClient;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.dto.VerificationStatusResponse;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.coryd.profile.service.DriverDocumentService;
import org.coryd.profile.service.DriverProfileService;
import org.coryd.profile.service.RiderProfileService;
import org.coryd.profile.service.VehicleService;
import org.coryd.profile.service.VerificationAggregationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The seeder runs on every dev restart - it must seed each account once, not re-approve (and re-notify) every boot. */
class DataSeederTest {

    private final DriverProfileService driverProfileService = mock(DriverProfileService.class);
    private final VehicleService vehicleService = mock(VehicleService.class);
    private final VehicleRepository vehicleRepository = mock(VehicleRepository.class);
    private final DriverDocumentService driverDocumentService = mock(DriverDocumentService.class);
    private final RiderProfileService riderProfileService = mock(RiderProfileService.class);
    private final VerificationAggregationService verification = mock(VerificationAggregationService.class);
    private final IdentityAuthClient identityAuthClient = mock(IdentityAuthClient.class);
    private final RiderProfileRepository riderProfileRepository = mock(RiderProfileRepository.class);

    private final DataSeeder seeder = new DataSeeder(driverProfileService, vehicleService, vehicleRepository,
            driverDocumentService, riderProfileService, verification, identityAuthClient, riderProfileRepository);

    @BeforeEach
    void ids() {
        when(identityAuthClient.resolveUserId(anyString(), anyString(), any(UserRole.class)))
                .thenAnswer(inv -> "user-" + inv.getArgument(0));
        when(vehicleRepository.findByDriverId(anyString())).thenReturn(List.of());
        when(vehicleService.register(anyString(), anyString(), any())).thenReturn(new Vehicle());
    }

    /** The seeded values go through the same entry points a real driver does, so anything that would
     * be rejected from the app must be rejected here too rather than quietly living in dev data. */
    @Test
    void seededVehiclesAndProfilesCarryEverythingARealSubmissionWould() {
        when(verification.getStatus(anyString())).thenReturn(status(VerificationStatus.PENDING));
        when(riderProfileRepository.findByUserId(anyString())).thenReturn(Optional.empty());

        seeder.run(null);

        ArgumentCaptor<UpdateDriverProfileRequest> profiles = ArgumentCaptor.forClass(UpdateDriverProfileRequest.class);
        verify(driverProfileService, times(SeedFixtures.DRIVERS.size())).updateMine(anyString(), profiles.capture());
        assertThat(profiles.getAllValues()).allSatisfy(request -> {
            assertThat(request.idNumber()).isEqualTo(DataSeeder.licenceNumber(profiles.getAllValues().indexOf(request) + 1));
            assertThat(request.emergencyContactName()).isNotBlank();
            assertThat(request.emergencyContactPhone()).isNotBlank();
        });

        ArgumentCaptor<VehicleRegistrationRequest> vehicles = ArgumentCaptor.forClass(VehicleRegistrationRequest.class);
        verify(vehicleService, times(SeedFixtures.DRIVERS.size())).register(anyString(), anyString(), vehicles.capture());
        assertThat(vehicles.getAllValues()).allSatisfy(request -> {
            assertThat(request.plateNumber()).matches("[A-Z]{3}[0-9]{3}[A-Z]{2}");
            assertThat(request.exteriorPhotoPublicId()).isNotBlank();
            assertThat(request.interiorPhotoPublicId()).isNotBlank();
            assertThat(request.color()).isNotBlank();
        });
        assertThat(vehicles.getAllValues()).extracting(VehicleRegistrationRequest::plateNumber)
                .as("a duplicate plate would fail registration for the second driver")
                .doesNotHaveDuplicates();
    }

    @Test
    void alreadySeededAccountsAreLeftAlone() {
        when(verification.getStatus(anyString())).thenReturn(status(VerificationStatus.APPROVED));
        when(riderProfileRepository.findByUserId(anyString())).thenReturn(Optional.of(new RiderProfile()));

        seeder.run(null);

        verify(verification, never()).decide(anyString(), any(), any());
        verify(driverProfileService, never()).updateMine(anyString(), any());
        verify(riderProfileService, never()).updateMine(anyString(), any());
    }

    @Test
    void newAccountsAreSeededAndApprovedOnce() {
        when(verification.getStatus(anyString())).thenReturn(status(VerificationStatus.PENDING));
        when(riderProfileRepository.findByUserId(anyString())).thenReturn(Optional.empty());

        seeder.run(null);

        verify(verification, times(SeedFixtures.DRIVERS.size())).decide(anyString(), eq(UserRole.OPS_ADMIN), any());
        verify(riderProfileService, times(SeedFixtures.RIDERS.size())).updateMine(anyString(), any());
    }

    private static VerificationStatusResponse status(VerificationStatus overall) {
        return new VerificationStatusResponse(null, null, null, null, List.of(), overall, null, List.of());
    }
}
