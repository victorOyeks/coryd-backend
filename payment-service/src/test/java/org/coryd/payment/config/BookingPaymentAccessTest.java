package org.coryd.payment.config;

import org.coryd.common.config.SecurityBeans;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.coryd.common.security.filter.JwtFilter;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.coryd.payment.client.TripServiceClient;
import org.coryd.payment.controller.PaymentController;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.security.BookingPaymentAccess;
import org.coryd.payment.service.PaymentHistoryService;
import org.coryd.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A booking's payment is readable by the rider who booked, that trip's driver, ops with MONEY_VIEW and other services -
 * not by any other rider or driver.
 */
@WebMvcTest(controllers = PaymentController.class)
@Import({SecurityConfig.class, SecurityBeans.class, JwtFilter.class, JwtUtil.class, TokenRevocationRegistry.class, BookingPaymentAccess.class})
@TestPropertySource(properties = {
        "services.internal.api-key=" + BookingPaymentAccessTest.KEY,
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789"
})
class BookingPaymentAccessTest {

    static final String KEY = "test-internal-key";

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean org.springframework.data.jpa.mapping.JpaMetamodelMappingContext jpaMappingContext;
    @MockBean PaymentTransactionRepository transactions;
    @MockBean TripServiceClient tripServiceClient;
    @MockBean PaymentService paymentService;
    @MockBean PaymentHistoryService paymentHistoryService;
    @MockBean org.coryd.payment.gateway.PaymentGatewayClient gatewayClient;

    @BeforeEach
    void booking() {
        PaymentTransaction payment = Mockito.mock(PaymentTransaction.class);
        when(payment.getRiderId()).thenReturn("rider-a");
        when(transactions.findFirstByBookingIdOrderByCreatedAtDesc("booking-1")).thenReturn(Optional.of(payment));
        when(tripServiceClient.fetchPaymentView("booking-1")).thenReturn(
                new TripServiceClient.BookingPaymentView("booking-1", "rider-a", "driver-a", null, null, null));
    }

    private MockHttpServletRequestBuilder as(String bookingId, String userId, UserRole role, OpsRole opsRole) {
        return get("/payments/bookings/" + bookingId)
                .header("Authorization", "Bearer " + jwtUtil.generateToken(userId, "0801", "A", "B", role, opsRole));
    }

    @Test
    void anotherRiderOrAnotherDriverCantReadIt() throws Exception {
        mockMvc.perform(as("booking-1", "rider-b", UserRole.RIDER, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You can only view payments for your own bookings"));
        mockMvc.perform(as("booking-1", "driver-b", UserRole.DRIVER, null)).andExpect(status().isForbidden());
        // a rider can't pass as the trip's driver
        mockMvc.perform(as("booking-1", "driver-a", UserRole.RIDER, null)).andExpect(status().isForbidden());
        mockMvc.perform(get("/payments/bookings/booking-1")).andExpect(status().isUnauthorized());
    }

    @Test
    void theRiderTheTripsDriverOpsWithMoneyViewAndServicesCan() throws Exception {
        mockMvc.perform(as("booking-1", "rider-a", UserRole.RIDER, null)).andExpect(status().isOk());
        mockMvc.perform(as("booking-1", "driver-a", UserRole.DRIVER, null)).andExpect(status().isOk());
        mockMvc.perform(as("booking-1", "ops-1", UserRole.OPS_ADMIN, OpsRole.SUPPORT)).andExpect(status().isOk());
        mockMvc.perform(as("booking-1", "ops-2", UserRole.OPS_ADMIN, OpsRole.VERIFICATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your ops role (VERIFICATION) can't view money"));
        mockMvc.perform(as("booking-1", "rider-b", UserRole.RIDER, null).header(InternalApiKeyFilter.HEADER, KEY))
                .andExpect(status().isOk());
    }

    @Test
    void whenTripServiceCantSayWhoTheDriverIsADriverIsRefusedAndNoPaymentIsA404() throws Exception {
        when(tripServiceClient.fetchPaymentView(anyString())).thenThrow(new IllegalStateException("down"));
        mockMvc.perform(as("booking-1", "driver-a", UserRole.DRIVER, null)).andExpect(status().isForbidden());
        when(paymentService.getByBooking("booking-2")).thenThrow(new ResourceNotFoundException("No payment found for this booking"));
        mockMvc.perform(as("booking-2", "rider-b", UserRole.RIDER, null)).andExpect(status().isNotFound());
    }
}
