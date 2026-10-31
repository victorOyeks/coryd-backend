package org.coryd.trip.controller;

import org.coryd.common.enums.RouteStatus;
import org.coryd.trip.dto.LocationResponse;
import org.coryd.trip.dto.SharedTripResponse;
import org.coryd.trip.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShareControllerTest {

    private final BookingService bookingService = mock(BookingService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ShareController(bookingService)).build();

    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    @Test
    void aBrowserGetsThePageWithoutAnyTripDataInIt() throws Exception {
        mvc.perform(get("/shared/abc.def").header("Accept", BROWSER_ACCEPT))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>CoRyd trip</title>")));
        verifyNoInteractions(bookingService); // the page asks for the trip itself, as JSON
    }

    @Test
    void theAppAndThePageGetJson() throws Exception {
        when(bookingService.getSharedTripView("abc.def")).thenReturn(new SharedTripResponse(
                "Ikorodu, Nigeria", "Eko Hotel Roundabout, Lagos, Nigeria", Instant.parse("2026-10-29T08:00:00Z"), null,
                "Kunle Adeyemi", null, true, "Toyota", "Corolla", "KJA001XA", true,
                LocationResponse.unavailable("Waiting for the driver's location to come through."),
                RouteStatus.PUBLISHED, 6.6194, 3.5105, 6.4281, 3.4219, null, "Silver"));

        mvc.perform(get("/shared/abc.def").header("Accept", "application/json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.driverName").value("Kunle Adeyemi"))
                .andExpect(jsonPath("$.data.tripStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.originLat").value(6.6194));
    }
}
