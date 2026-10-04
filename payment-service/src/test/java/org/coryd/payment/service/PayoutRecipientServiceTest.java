package org.coryd.payment.service;

import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Saved bank accounts are personal - read back only by the driver themselves or ops. */
class PayoutRecipientServiceTest {

    private final PayoutRecipientRepository repository = mock(PayoutRecipientRepository.class);
    private final PayoutRecipientService service = new PayoutRecipientService(repository,
            mock(PaystackGatewayClient.class), mock(CommissionProperties.class));

    @Test
    void aDriverReadsTheirOwnAccountsAndOpsCanReadAnyones() {
        when(repository.findByDriverIdOrderByCreatedAtDesc("d-1")).thenReturn(List.of());

        assertThat(service.list("d-1", "d-1", UserRole.DRIVER)).isEmpty();
        assertThat(service.list("d-1", "ops-1", UserRole.OPS_ADMIN)).isEmpty();
    }

    @Test
    void nobodyElseCanReadADriversBankDetails() {
        assertThatThrownBy(() -> service.list("d-1", "rider-1", UserRole.RIDER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.list("d-1", "d-2", UserRole.DRIVER)).isInstanceOf(UnauthorizedException.class);
        verify(repository, never()).findByDriverIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    void anySavedAccountCountsForPosting() {
        when(repository.existsByDriverId("d-1")).thenReturn(true);

        assertThat(service.hasPayoutAccount("d-1")).isTrue();
        assertThat(service.hasPayoutAccount("d-2")).isFalse();
    }
}
