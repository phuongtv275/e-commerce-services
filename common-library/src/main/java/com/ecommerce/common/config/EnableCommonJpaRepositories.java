package com.ecommerce.common.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bật quét tường minh JPA Entities và Repositories của common-library.
 * Sử dụng khi downstream microservice cấu hình @EnableJpaRepositories riêng
 * khiến Spring Boot tắt auto-scan mặc định.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@EntityScan(basePackages = "com.ecommerce.common")
@EnableJpaRepositories(basePackages = "com.ecommerce.common")
public @interface EnableCommonJpaRepositories {
}
