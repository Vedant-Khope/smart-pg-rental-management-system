package com.smartpg.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Enables JPA Auditing across the entire application.
 *
 * <p>Without @EnableJpaAuditing, the @CreatedDate, @LastModifiedDate,
 * @CreatedBy, and @LastModifiedBy annotations in BaseEntity do nothing.
 *
 * <p>auditorAwareRef links to our AuditorAware bean which resolves
 * the currently authenticated user from Spring Security's context.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class AuditConfig {

    /**
     * Spring Data JPA calls getCurrentAuditor() before every INSERT and UPDATE
     * to determine who performed the operation.
     *
     * <p>This bean reads the current principal from Spring Security's
     * SecurityContextHolder. If no authentication is present (e.g., during
     * a public registration flow), it falls back to "SYSTEM".
     *
     * @return AuditorAware implementation that resolves the current user's name
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            Authentication authentication = SecurityContextHolder
                    .getContext()
                    .getAuthentication();

            // No authentication present (unauthenticated / public endpoint)
            if (authentication == null || !authentication.isAuthenticated()) {
                return Optional.of("SYSTEM");
            }

            // Returns the username (email) of the logged-in user
            return Optional.of(authentication.getName());
        };
    }
}
