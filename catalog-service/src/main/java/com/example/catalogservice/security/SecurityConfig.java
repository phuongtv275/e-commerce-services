package com.example.catalogservice.security;

import com.ecommerce.common.filter.InternalServiceFilter;
import com.ecommerce.common.filter.TrustedHeaderAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtEntryPoint jwtEntryPoint;
    private final AccessDenied accessDenied;
    private final InternalServiceFilter internalServiceFilter;
    private final TrustedHeaderAuthenticationFilter trustedHeaderAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtEntryPoint)
                        .accessDeniedHandler(accessDenied)
                )
                .authorizeHttpRequests(req -> req
                        // Public catalog read endpoints
                        .requestMatchers(HttpMethod.GET, "/api/v1/catalog/categories", "/api/v1/catalog/categories/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/catalog/brands", "/api/v1/catalog/brands/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/catalog/products/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/catalog/search").permitAll()
                        // Public Actuator endpoints
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Internal inter-service endpoints (authenticated via InternalServiceFilter)
                        .requestMatchers("/internal/**").authenticated()
                        // Administrator-only endpoints
                        .requestMatchers("/api/v1/catalog/admin/reindex", "/api/v1/catalog/admin/reindex/**").hasRole("ADMINISTRATOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/catalog/admin/categories/**").hasRole("ADMINISTRATOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/catalog/admin/brands/**").hasRole("ADMINISTRATOR")
                        // Admin endpoints accessible by staff or administrator
                        .requestMatchers("/api/v1/catalog/admin/**").hasAnyRole("CATALOG_STAFF", "ADMINISTRATOR")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(internalServiceFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(trustedHeaderAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
