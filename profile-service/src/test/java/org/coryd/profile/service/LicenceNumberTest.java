package org.coryd.profile.service;

import org.coryd.common.exception.BadRequestException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenceNumberTest {

    @ParameterizedTest
    @CsvSource({
            "AAD2320823198, AAD2320823198",
            "aad 2320-823198, AAD2320823198",
            "FKJ49409AA13, FKJ49409AA13",
            "A12345678BC, A12345678BC",
            "  lag12345678 , LAG12345678"
    })
    void realLookingNumbersAreTidiedAndAccepted(String entered, String stored) {
        assertThat(DriverProfileService.normalizeLicenceNumber(entered)).isEqualTo(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "SEED-ID-6", "12345678", "ABCDEFGHIJ", "AB12/34567", "A1234567890123456789012"})
    void thingsThatCannotBeALicenceNumberAreRejected(String entered) {
        assertThatThrownBy(() -> DriverProfileService.normalizeLicenceNumber(entered))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("letters and numbers only");
    }
}
