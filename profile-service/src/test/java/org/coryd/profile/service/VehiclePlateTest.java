package org.coryd.profile.service;

import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.VehicleRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A plate in another spelling is the same plate, and editing onto another car's plate is a clean 400. */
class VehiclePlateTest {

    private final VehicleRepository repository = mock(VehicleRepository.class);
    private final VehicleService service = new VehicleService(repository, mock(ReplacedFiles.class));

    private static VehicleRegistrationRequest request(String plate) {
        return new VehicleRegistrationRequest("Toyota", "Corolla", 2015, "Silver", plate, 4,
                "https://img/ext.png", "coryd/uploads/ext", "https://img/int.png", "coryd/uploads/int", true, true);
    }

    @Test
    void platesAreStoredTrimmedAsWritten() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        Vehicle saved = service.register("driver-1", "driver-1", request(" lag-123  ab "));

        assertThat(saved.getPlateNumber()).isEqualTo("lag-123 ab");
    }

    @Test
    void aPlateAlreadyRegisteredInAnotherSpellingIsRefused() {
        when(repository.existsByPlateKey("LAG123AB", "")).thenReturn(true);

        assertThatThrownBy(() -> service.register("driver-1", "driver-1", request("lag 123-ab")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(VehicleService.PLATE_TAKEN);
    }

    @Test
    void editingOntoAnotherCarsPlateIsA400NotADatabaseError() {
        Vehicle mine = Vehicle.builder().id("v-1").driverId("driver-1").plateNumber("ABC111XY")
                .verificationStatus(VerificationStatus.APPROVED).build();
        when(repository.findById("v-1")).thenReturn(Optional.of(mine));
        when(repository.existsByPlateKey("LAG123AB", "v-1")).thenReturn(true);

        assertThatThrownBy(() -> service.update("v-1", "driver-1", request("LAG123AB")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(VehicleService.PLATE_TAKEN);
        verify(repository, never()).save(any());
    }

    @Test
    void keepingYourOwnPlateOnAnEditIsFine() {
        Vehicle mine = Vehicle.builder().id("v-1").driverId("driver-1").plateNumber("ABC111XY")
                .verificationStatus(VerificationStatus.APPROVED).build();
        when(repository.findById("v-1")).thenReturn(Optional.of(mine));
        when(repository.existsByPlateKey("ABC111XY", "v-1")).thenReturn(false);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.update("v-1", "driver-1", request("abc-111 xy")).getPlateNumber()).isEqualTo("abc-111 xy");
    }

    @Test
    void somethingThatIsNotAPlateIsRefused() {
        assertThatThrownBy(() -> service.register("driver-1", "driver-1", request(" -- ")))
                .isInstanceOf(BadRequestException.class);
    }
}
