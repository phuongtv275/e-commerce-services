package com.example.paymentservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
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
class PaymentServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
