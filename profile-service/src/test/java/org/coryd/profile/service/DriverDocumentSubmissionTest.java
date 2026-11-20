package org.coryd.profile.service;

import org.coryd.common.enums.DocumentType;
import org.coryd.common.exception.BadRequestException;
import org.coryd.profile.dto.CreateDriverDocumentRequest;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A driver's document goes on their own vehicle and isn't already expired. */
class DriverDocumentSubmissionTest {

    private final VehicleRepository vehicles = mock(VehicleRepository.class);
    private final DriverDocumentService service = new DriverDocumentService(mock(DriverDocumentRepository.class),
            mock(ReplacedFiles.class), vehicles);

    private static CreateDriverDocumentRequest doc(String vehicleId, LocalDate expiresOn) {
        return new CreateDriverDocumentRequest(DocumentType.INSURANCE, "https://img/ins.png", "coryd/uploads/ins", expiresOn, vehicleId);
    }

    @Test
    void aDocumentOnSomeoneElsesVehicleIsRefused() {
        when(vehicles.findById("v-other")).thenReturn(Optional.of(Vehicle.builder().id("v-other").driverId("driver-2").build()));

        assertThatThrownBy(() -> service.checkSubmission("driver-1", doc("v-other", LocalDate.now().plusYears(1))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("isn't registered to you");
    }

    @Test
    void aDocumentOnAnUnknownVehicleIsRefused() {
        when(vehicles.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkSubmission("driver-1", doc("nope", LocalDate.now().plusYears(1))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anExpiredDocumentIsRefused() {
        assertThatThrownBy(() -> service.checkSubmission("driver-1", doc(null, LocalDate.of(2020, 1, 1))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already expired");
    }

    @Test
    void aCurrentDocumentOnYourOwnVehiclePasses() {
        when(vehicles.findById("v-1")).thenReturn(Optional.of(Vehicle.builder().id("v-1").driverId("driver-1").build()));

        assertThatCode(() -> service.checkSubmission("driver-1", doc("v-1", LocalDate.now().plusMonths(6))))
                .doesNotThrowAnyException();
    }
}
