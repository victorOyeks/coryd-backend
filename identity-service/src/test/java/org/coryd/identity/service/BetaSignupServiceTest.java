package org.coryd.identity.service;

import org.coryd.identity.dto.BetaSignupRequest;
import org.coryd.identity.entity.BetaSignup;
import org.coryd.identity.repository.BetaSignupRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BetaSignupServiceTest {

    private final BetaSignupRepository repository = mock(BetaSignupRepository.class);
    private final BetaSignupService service = new BetaSignupService(repository);

    @Test
    void theEmailForTestFlightOrPlayIsKeptLowerCased() {
        when(repository.findByWhatsappNumber(anyString())).thenReturn(Optional.empty());

        service.join(new BetaSignupRequest("Ada Obi", "0803 123 4567", "  Ada.Obi@Gmail.com ",
                BetaSignup.Intent.RIDE, "Ikorodu to VI", BetaSignup.Device.IPHONE));

        ArgumentCaptor<BetaSignup> saved = ArgumentCaptor.forClass(BetaSignup.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ada.obi@gmail.com");
        assertThat(saved.getValue().getWhatsappNumber()).isEqualTo("08031234567");
    }

    @Test
    void signingUpAgainUpdatesTheEmailOnTheSameEntry() {
        BetaSignup existing = BetaSignup.builder().whatsappNumber("08031234567").name("Ada").email("old@x.com")
                .willMostly(BetaSignup.Intent.RIDE).usualRoute("Yaba").device(BetaSignup.Device.ANDROID).build();
        when(repository.findByWhatsappNumber("08031234567")).thenReturn(Optional.of(existing));

        service.join(new BetaSignupRequest("Ada Obi", "08031234567", "new@x.com",
                BetaSignup.Intent.DRIVE, "Ikorodu to VI", BetaSignup.Device.IPHONE));

        verify(repository).save(existing);
        assertThat(existing.getEmail()).isEqualTo("new@x.com");
    }
}
