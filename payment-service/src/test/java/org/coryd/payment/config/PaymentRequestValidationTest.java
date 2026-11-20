package org.coryd.payment.config;

import org.coryd.common.config.SecurityBeans;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.filter.JwtFilter;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.coryd.payment.controller.EarningsController;
import org.coryd.payment.controller.PaymentController;
import org.coryd.payment.service.EarningsService;
import org.coryd.payment.service.PaymentHistoryService;
import org.coryd.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A payment request missing what it needs is a 400 that says so - an empty bookingId used to come back a 500. */
@WebMvcTest(controllers = {PaymentController.class, EarningsController.class})
@Import({SecurityConfig.class, SecurityBeans.class, JwtFilter.class, JwtUtil.class, TokenRevocationRegistry.class})
@TestPropertySource(properties = {
        "services.internal.api-key=test-internal-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789"
})
class PaymentRequestValidationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    /** The application class enables JPA auditing; a web slice has no JPA. */
    @MockBean org.springframework.data.jpa.mapping.JpaMetamodelMappingContext jpaMappingContext;
    @MockBean EarningsService earningsService;
    @MockBean PaymentService paymentService;
    @MockBean PaymentHistoryService paymentHistoryService;
    @MockBean org.coryd.payment.gateway.PaymentGatewayClient gatewayClient;
    @MockBean org.coryd.payment.security.BookingPaymentAccess bookingPaymentAccess;

    private MockHttpServletRequestBuilder as(String userId, UserRole role, MockHttpServletRequestBuilder request) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + jwtUtil.generateToken(userId, "0801", "A", "B", role, null));
    }

    @Test
    void anEmptyMissingOrBlankBookingIdIsA400NotA500() throws Exception {
        for (String body : new String[]{"{\"bookingId\":\"\"}", "{\"bookingId\":\"   \"}", "{}", "{\"bookingId\":null}"}) {
            mockMvc.perform(as("rider-1", UserRole.RIDER, post("/payments/initiate").content(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("bookingId: is required - the booking you're paying for"));
        }
        verify(paymentService, never()).initiate(any(), anyString());
    }

    @Test
    void aRealBookingIdGoesThrough() throws Exception {
        mockMvc.perform(as("rider-1", UserRole.RIDER, post("/payments/initiate").content("{\"bookingId\":\"booking-1\"}")))
                .andExpect(status().isOk());
        verify(paymentService).initiate(any(), anyString());
    }

    @Test
    void aRepaymentOfZeroOrLessIsA400AndLeavingTheAmountOutStillPaysEverything() throws Exception {
        mockMvc.perform(as("driver-1", UserRole.DRIVER, post("/earnings/drivers/driver-1/repay").content("{\"amount\":-5}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("amount: must be more than zero - or leave it out to pay everything owed"));
        mockMvc.perform(as("driver-1", UserRole.DRIVER, post("/earnings/drivers/driver-1/repay").content("{}")))
                .andExpect(status().isOk());
        verify(paymentService).initiateRepayment(anyString(), anyString(), any(), any(), any());
    }
}
