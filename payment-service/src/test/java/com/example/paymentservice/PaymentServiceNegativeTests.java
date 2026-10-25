package com.example.paymentservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:payment_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "vnpay.tmn-code=TESTTMN",
        "vnpay.hash-secret=SECRET12345678901234567890123456789012",
        "vnpay.ip-address=103.1.2.3",
        "vnpay.return-url=https://example.com/return",
        "vnpay.ipn-url=https://example.com/ipn"
})
class PaymentServiceNegativeTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Negative: Non-existent endpoint should return 404 Not Found")
    void testNonExistentEndpointReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/non-existent-resource"))
                .andExpect(status().isNotFound());
    }
}
