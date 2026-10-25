package com.example.inventoryservice.security;

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
                        // Actuator health & info public
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Internal APIs authenticated via InternalServiceFilter
                        .requestMatchers("/internal/**").authenticated()
                        // Stock adjustment and movements require staff or admin
                        .requestMatchers(HttpMethod.POST, "/api/v1/inventory/stocks/*/adjust").hasAnyRole("INVENTORY_STAFF", "ADMINISTRATOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/inventory/stocks/*/movements").hasAnyRole("INVENTORY_STAFF", "ADMINISTRATOR")
                        // Stock query requires authenticated user
                        .requestMatchers(HttpMethod.GET, "/api/v1/inventory/stocks/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(internalServiceFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(trustedHeaderAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
