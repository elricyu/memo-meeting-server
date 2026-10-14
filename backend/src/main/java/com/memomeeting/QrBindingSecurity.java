package com.memomeeting;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Native unbound devices may claim a high-entropy QR token; they gain no user session here. */
@Configuration
public class QrBindingSecurity {
    @Bean @Order(0) SecurityFilterChain qrClaims(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/v1/devices/binding-claims","/api/v1/devices/binding-claims/scan","/api/v1/devices/binding-claims/recover","/api/v1/devices/binding-claims/check")
            .csrf(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(c->c.requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/devices/binding-claims","/api/v1/devices/binding-claims/scan","/api/v1/devices/binding-claims/recover","/api/v1/devices/binding-claims/check").permitAll().anyRequest().denyAll()).build();
    }
}
