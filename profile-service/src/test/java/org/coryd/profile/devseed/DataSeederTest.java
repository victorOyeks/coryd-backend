package org.coryd.profile.devseed;

import org.coryd.common.devseed.SeedFixtures;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.client.IdentityAuthClient;
import org.coryd.profile.dto.VerificationStatusResponse;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.coryd.profile.service.DriverProfileService;
import org.coryd.profile.service.RiderProfileService;
import org.coryd.profile.service.VerificationAggregationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
    private final VehicleRepository vehicleRepository = mock(VehicleRepository.class);
    private final DriverDocumentRepository driverDocumentRepository = mock(DriverDocumentRepository.class);
    private final RiderProfileService riderProfileService = mock(RiderProfileService.class);
    private final VerificationAggregationService verification = mock(VerificationAggregationService.class);
    private final IdentityAuthClient identityAuthClient = mock(IdentityAuthClient.class);
    private final RiderProfileRepository riderProfileRepository = mock(RiderProfileRepository.class);

    private final DataSeeder seeder = new DataSeeder(driverProfileService, vehicleRepository, driverDocumentRepository,
            riderProfileService, verification, identityAuthClient, riderProfileRepository);

    @BeforeEach
    void ids() {
        when(identityAuthClient.resolveUserId(anyString(), anyString(), any(UserRole.class)))
                .thenAnswer(inv -> "user-" + inv.getArgument(0));
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
