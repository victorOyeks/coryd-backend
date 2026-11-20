package org.coryd.profile.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.service.RiderProfileService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** GET /riders/{userId} shows another rider's name and photo - never their email or emergency contact. */
class RiderProfileControllerTest {

    private final RiderProfileService service = mock(RiderProfileService.class);
    private final RiderProfileController controller = new RiderProfileController(service);

    @Test
    void anotherRidersProfileCarriesNoPrivateDetails() {
        when(service.get("rider-1")).thenReturn(RiderProfile.builder().userId("rider-1").firstName("Ada").lastName("Obi")
                .photoUrl("https://img/ada.jpg").email("ada@example.com")
                .emergencyContactName("Mum").emergencyContactPhone("08030000000").build());

        JsonNode json = new ObjectMapper().registerModule(new JavaTimeModule()).valueToTree(controller.get("rider-1").data());

        assertThat(json.path("firstName").asText()).isEqualTo("Ada");
        assertThat(json.path("photoUrl").asText()).isEqualTo("https://img/ada.jpg");
        assertThat(json.has("email")).isFalse();
        assertThat(json.has("emergencyContactName")).isFalse();
        assertThat(json.has("emergencyContactPhone")).isFalse();
    }
}
