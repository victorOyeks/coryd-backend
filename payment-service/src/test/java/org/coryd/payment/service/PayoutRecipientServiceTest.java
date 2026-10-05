package org.coryd.payment.service;

import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.dto.PayoutRecipientRequest;
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
    private final PaystackGatewayClient paystack = mock(PaystackGatewayClient.class);
    private final PayoutRecipientService service = new PayoutRecipientService(repository, paystack, new CommissionProperties(),
            new ResolvedAccountCache());

    @Test
    void savingRightAfterTheLookupReusesTheBanksAnswerInsteadOfAskingAgain() {
        when(paystack.resolveAccountNumber("0123451525", "057"))
                .thenReturn(new PaystackGatewayClient.ResolvedAccount("0123451525", "ADA OBI"));
        when(paystack.createSubaccount(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PaystackGatewayClient.SubaccountResult("ACCT_1", "42"));
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        service.resolve("d-1", "0123451525", "057");
        var saved = service.add("d-1", "d-1", new PayoutRecipientRequest("Zenith Bank", "057", "0123451525"));

        assertThat(saved.getAccountName()).isEqualTo("ADA OBI");
        org.mockito.Mockito.verify(paystack, org.mockito.Mockito.times(1)).resolveAccountNumber("0123451525", "057");
    }

    @Test
    void anotherDriversLookupNeverVouchesForThisSave() {
        when(paystack.resolveAccountNumber("0123451525", "057"))
                .thenReturn(new PaystackGatewayClient.ResolvedAccount("0123451525", "ADA OBI"));
        when(paystack.createSubaccount(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PaystackGatewayClient.SubaccountResult("ACCT_1", "42"));
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        service.resolve("d-2", "0123451525", "057");
        service.add("d-1", "d-1", new PayoutRecipientRequest("Zenith Bank", "057", "0123451525"));

        org.mockito.Mockito.verify(paystack, org.mockito.Mockito.times(2)).resolveAccountNumber("0123451525", "057");
    }

    @Test
    void savingAnAccountResolvesItAndCreatesTheSubaccountFaresSplitTo() {
        when(paystack.resolveAccountNumber("0123451525", "057"))
                .thenReturn(new PaystackGatewayClient.ResolvedAccount("0123451525", "ADA OBI"));
        when(paystack.createSubaccount("ADA OBI", "0123451525", "057", new java.math.BigDecimal("95.00")))
                .thenReturn(new PaystackGatewayClient.SubaccountResult("ACCT_1", "42"));
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        var saved = service.add("d-1", "d-1", new PayoutRecipientRequest("Zenith Bank", "057", "0123451525"));

        assertThat(saved.getAccountName()).as("the bank's name, never the client's").isEqualTo("ADA OBI");
        assertThat(saved.getBankName()).isEqualTo("Zenith Bank");
        assertThat(saved.getGatewaySubaccountCode()).isEqualTo("ACCT_1");
        assertThat(saved.getGatewaySubaccountId()).isEqualTo("42");
        assertThat(saved.isDefault()).as("first account").isTrue();
    }

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
