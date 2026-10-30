package org.coryd.payment.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClaneWebhookControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ClaneWebhookController()).build();

    @Test
    void aClaneNotificationIsAcknowledged() throws Exception {
        mvc.perform(post("/payments/clane/webhook").contentType("application/json").content("{\"event\":\"payment.success\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void anEmptyCallIsAcknowledgedToo() throws Exception {
        mvc.perform(post("/payments/clane/webhook")).andExpect(status().isOk());
    }
}
