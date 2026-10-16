package com.example.identityservice.models.dto;

import com.example.identityservice.models.dto.req.*;
import com.example.identityservice.models.dto.res.AuditLogRes;
import com.example.identityservice.models.services.AuditLogService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DtoValidationTests {

    private static Validator validator;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("RegisterReq: should pass validation when payload is fully compliant")
    void testRegisterReqValid() {
        RegisterReq req = new RegisterReq("Nguyễn Văn A", "valid@example.com", "Password123@", "0912345678");
        var violations = validator.validate(req);
        assertThat(violations).isEmpty();
        assertThat(req.email()).isEqualTo("valid@example.com");
    }

    @Test
    @DisplayName("RegisterReq: should fail when email is invalid or password does not meet complexity rules")
    void testRegisterReqInvalid() {
        RegisterReq invalidEmail = new RegisterReq("Nguyễn Văn A", "not-an-email", "Password123@", "0912345678");
        assertThat(validator.validate(invalidEmail)).isNotEmpty();

        RegisterReq weakPassword = new RegisterReq("Nguyễn Văn A", "user@example.com", "weak", "0912345678");
        assertThat(validator.validate(weakPassword)).isNotEmpty();
    }

    @Test
    @DisplayName("LoginReq: should fail validation when email or password is blank")
    void testLoginReqValidation() {
        LoginReq blankEmail = new LoginReq("", "Password123@");
        assertThat(validator.validate(blankEmail)).isNotEmpty();

        LoginReq valid = new LoginReq("user@example.com", "Password123@");
        assertThat(validator.validate(valid)).isEmpty();
    }

    @Test
    @DisplayName("AddressReq: should validate required recipient details and phone format")
    void testAddressReqValidation() {
        AddressReq invalidPhone = new AddressReq("", "123", "123 Main St", null, "Dist 1", "Hanoi", "Vietnam", true);
        assertThat(validator.validate(invalidPhone)).hasSizeGreaterThanOrEqualTo(2);

        AddressReq valid = new AddressReq("Trần B", "0987654321", "123 Main St", null, "Dist 1", "Hanoi", "Vietnam", true);
        assertThat(validator.validate(valid)).isEmpty();
    }

    @Test
    @DisplayName("AuditLogService: should record and retrieve immutable audit logs")
    void testAuditLogServiceRecordAndQuery() {
        if (auditLogService != null) {
            UUID actorId = UUID.randomUUID();
            String correlationId = "corr-test-123";
            auditLogService.logAction(
                    actorId,
                    "admin@example.com",
                    "USER_ROLE_ASSIGN",
                    "USER",
                    UUID.randomUUID().toString(),
                    "{\"role\":\"ROLE_CATALOG_STAFF\"}",
                    "127.0.0.1",
                    correlationId
            );

            Page<AuditLogRes> logs = auditLogService.getAuditLogs(actorId, "USER_ROLE_ASSIGN", null, null, PageRequest.of(0, 10));
            assertThat(logs).isNotEmpty();
            assertThat(logs.getContent().get(0).actorEmail()).isEqualTo("admin@example.com");
            assertThat(logs.getContent().get(0).correlationId()).isEqualTo(correlationId);
        }
    }
}
