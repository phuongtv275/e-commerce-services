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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.domain.EntityScanPackages;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Slf4j
@AutoConfiguration(after = {JpaRepositoriesAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@EnableConfigurationProperties(CommonLibrarySecurityProperties.class)
@Import(CommonLibraryAutoConfiguration.CommonLibraryPackagesRegistrar.class)
public class CommonLibraryAutoConfiguration {

    public static class CommonLibraryPackagesRegistrar implements ImportBeanDefinitionRegistrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
            AutoConfigurationPackages.register(registry, "com.ecommerce.common");
            EntityScanPackages.register(registry, "com.ecommerce.common");
        }
    }

    // --- Distributed Tracing & Security Filters ---

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public TrustedHeaderAuthenticationFilter trustedHeaderAuthenticationFilter(
            @Value("${spring.application.name:unknown-service}") String applicationName,
            CommonLibrarySecurityProperties securityProperties,
            @Autowired(required = false) PublicKey publicKey,
            @Autowired(required = false) ObjectMapper objectMapper) {

        PublicKey keyToUse = publicKey;
        if (keyToUse == null && securityProperties.getGatewayAssertion().getPublicKey() != null
                && !securityProperties.getGatewayAssertion().getPublicKey().isBlank()) {
            keyToUse = parseRsaPublicKey(securityProperties.getGatewayAssertion().getPublicKey());
        }

        ObjectMapper mapper = (objectMapper != null) ? objectMapper : createDefaultObjectMapper();
        return new TrustedHeaderAuthenticationFilter(applicationName, keyToUse, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(name = "trustedHeaderFilterRegistration")
    @ConditionalOnBean(TrustedHeaderAuthenticationFilter.class)
    public FilterRegistrationBean<TrustedHeaderAuthenticationFilter> trustedHeaderFilterRegistration(
            TrustedHeaderAuthenticationFilter filter) {
        FilterRegistrationBean<TrustedHeaderAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // Tránh servlet container tự chạy ngoài SecurityFilterChain
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public InternalServiceFilter internalServiceFilter(
            CommonLibrarySecurityProperties securityProperties,
            @Autowired(required = false) ObjectMapper objectMapper) {
        ObjectMapper mapper = (objectMapper != null) ? objectMapper : createDefaultObjectMapper();
        return new InternalServiceFilter(securityProperties.getInternalServiceKeys(), mapper);
    }

    @Bean
    @ConditionalOnMissingBean(name = "internalServiceFilterRegistration")
    @ConditionalOnBean(InternalServiceFilter.class)
    public FilterRegistrationBean<InternalServiceFilter> internalServiceFilterRegistration(
            InternalServiceFilter filter) {
        FilterRegistrationBean<InternalServiceFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // Tránh servlet container tự chạy ngoài SecurityFilterChain
        return registration;
    }

    // --- Global Exception Handler ---

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    // --- Idempotent Consumer ---

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ProcessedEventRepository.class)
    public IdempotentClaimManager idempotentClaimManager(ProcessedEventRepository processedEventRepository,
                                                         @Autowired(required = false) org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new IdempotentClaimManager(processedEventRepository, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ProcessedEventRepository.class)
    public IdempotentConsumerHelper idempotentConsumerHelper(ProcessedEventRepository processedEventRepository,
                                                           IdempotentClaimManager idempotentClaimManager) {
        return new IdempotentConsumerHelper(processedEventRepository, idempotentClaimManager);
    }

    // --- Outbox Pattern ---

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({OutboxEventRepository.class, KafkaTemplate.class})
    public OutboxPublisher outboxPublisher(OutboxEventRepository outboxEventRepository,
                                           KafkaTemplate<String, String> kafkaTemplate) {
        return new OutboxPublisher(outboxEventRepository, kafkaTemplate);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean({OutboxEventRepository.class, KafkaTemplate.class})
    @EnableScheduling
    static class OutboxSchedulingConfiguration {
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(OutboxEventRepository.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public OutboxReplayController outboxReplayController(OutboxEventRepository outboxEventRepository) {
        return new OutboxReplayController(outboxEventRepository);
    }

    // --- Utilities ---

    private static PublicKey parseRsaPublicKey(String keyStr) {
        try {
            String cleanKey = keyStr
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(cleanKey);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(spec);
        } catch (Exception e) {
            log.warn("Failed to parse RSA public key from security.gateway-assertion.public-key: {}", e.getMessage());
            return null;
        }
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
