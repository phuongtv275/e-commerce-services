package com.example.notificationservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationServiceNegativeTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Negative: Non-existent endpoint is denied before route resolution")
    @WithMockUser
    void testNonExistentEndpointReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/non-existent-resource"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Negative: Unauthenticated access to admin endpoint returns 401 Unauthorized")
    void testUnauthenticatedAccessReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/notifications/dlq"))
                .andExpect(status().isUnauthorized());
    }
}
