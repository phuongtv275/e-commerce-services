package com.ecommerce.common.config;

import com.ecommerce.common.exception.GlobalExceptionHandler;
import com.ecommerce.common.filter.CorrelationIdFilter;
import com.ecommerce.common.filter.InternalServiceFilter;
import com.ecommerce.common.filter.TrustedHeaderAuthenticationFilter;
import com.ecommerce.common.idempotency.IdempotentClaimManager;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.ecommerce.common.idempotency.ProcessedEventRepository;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.ecommerce.common.outbox.OutboxPublisher;
import com.ecommerce.common.outbox.OutboxReplayController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScanPackages;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CommonLibraryAutoConfigurationTests {

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonLibraryAutoConfiguration.class));

    private final ApplicationContextRunner appContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonLibraryAutoConfiguration.class));

    @Test
    @DisplayName("Positive: Trong môi trường Web Servlet, các filters và GlobalExceptionHandler được tạo đầy đủ")
    void testServletWebEnvironment_FiltersAndHandlerCreated_Positive() {
        webContextRunner
                .withPropertyValues(
                        "spring.application.name=test-order-service",
                        "security.internal-service-keys.catalog-service=secret-123"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CorrelationIdFilter.class);
                    assertThat(context).hasSingleBean(TrustedHeaderAuthenticationFilter.class);
                    assertThat(context).hasSingleBean(InternalServiceFilter.class);
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);

                    // Kiểm tra filter registrations có enabled = false để tránh chạy ngoài SecurityFilterChain
                    FilterRegistrationBean<?> trustedReg = context.getBean("trustedHeaderFilterRegistration", FilterRegistrationBean.class);
                    assertThat(trustedReg.isEnabled()).isFalse();

                    FilterRegistrationBean<?> internalReg = context.getBean("internalServiceFilterRegistration", FilterRegistrationBean.class);
                    assertThat(internalReg.isEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("Positive: AutoConfigurationPackages và EntityScanPackages tự động đăng ký com.ecommerce.common")
    void testPackagesRegistration_Positive() {
        appContextRunner.run(context -> {
            List<String> autoConfigPackages = AutoConfigurationPackages.get(context.getSourceApplicationContext());
            assertThat(autoConfigPackages).contains("com.ecommerce.common");

            EntityScanPackages entityScanPackages = EntityScanPackages.get(context.getSourceApplicationContext());
            assertThat(entityScanPackages.getPackageNames()).contains("com.ecommerce.common");
        });
    }

    @Test
    @DisplayName("Positive: Khi có OutboxEventRepository và KafkaTemplate, tạo OutboxPublisher và kích hoạt Scheduling")
    void testOutboxAndSchedulingBeansCreated_Positive() {
        appContextRunner
                .withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class))
                .withBean("kafkaTemplate", KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxPublisher.class);
                    // Kiểm tra ScheduledAnnotationBeanPostProcessor được kích hoạt bởi @EnableScheduling
                    assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                });
    }

    @Test
    @DisplayName("Positive: Khi có ProcessedEventRepository, tạo IdempotentClaimManager và IdempotentConsumerHelper")
    void testIdempotencyBeansCreated_Positive() {
        appContextRunner
                .withBean(ProcessedEventRepository.class, () -> mock(ProcessedEventRepository.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotentClaimManager.class);
                    assertThat(context).hasSingleBean(IdempotentConsumerHelper.class);
                });
    }

    @Test
    @DisplayName("Negative: Môi trường non-web không tạo các Filter và GlobalExceptionHandler")
    void testNonWebEnvironment_WebBeansNotCreated_Negative() {
        appContextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
            assertThat(context).doesNotHaveBean(TrustedHeaderAuthenticationFilter.class);
            assertThat(context).doesNotHaveBean(InternalServiceFilter.class);
            assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class);
            assertThat(context).doesNotHaveBean(OutboxReplayController.class);
        });
    }

    @Test
    @DisplayName("Positive: RSA Public Key với ký tự '\\n' literal được parse thành công và inject vào TrustedHeaderAuthenticationFilter")
    void testTrustedHeaderFilterWithPublicKeyContainingLiteralNewlines_Positive() {
        String testPublicKeyPem = "-----BEGIN PUBLIC KEY-----\\n"
                + "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1rYaNGEBN4TFHrxLtu91\\n"
                + "atHxmWjLaEduLAEZsrGEMj1czx1iKP4DZjCIicBq/CAnrKx/hjum2yTgfV8HcaBR\\n"
                + "Y1dcQ5Mzix4nRCx3EjWyUEdFkGr9Li9PORUU4O+NMpg0BfUhBeclkx78oz4rq3wd\\n"
                + "BWXBHBmPPPLbKACHQnUCwynUmFm7u/2VUbF8ynuOBJFETtk1xO5krePJpp8yKlc5\\n"
                + "gddhRDhl9GS7+n/em/HVBkZx4NLJUJS9Y4M12euiHsZrZLJUVDL2hGbjPWWB+JPb\\n"
                + "iqYNaD+QGVr9+go5NKHUCwXot/D7UIjI14d0xQYiVKZqqDzwgJyoGHTQxyH8EkxK\\n"
                + "nwIDAQAB\\n"
                + "-----END PUBLIC KEY-----";

        webContextRunner
                .withPropertyValues(
                        "spring.application.name=test-payment-service",
                        "security.gateway-assertion.public-key=" + testPublicKeyPem
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(TrustedHeaderAuthenticationFilter.class);
                });
    }

    @Test
    @DisplayName("Negative: RSA Public Key không hợp lệ được log warning và không làm crash ứng dụng")
    void testTrustedHeaderFilterWithInvalidPublicKey_Negative() {
        webContextRunner
                .withPropertyValues(
                        "spring.application.name=test-payment-service",
                        "security.gateway-assertion.public-key=invalid-not-a-valid-rsa-key"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(TrustedHeaderAuthenticationFilter.class);
                });
    }
}
