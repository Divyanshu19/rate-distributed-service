package com.ratelimiter.distributed.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration.
 *
 * <h2>Security model</h2>
 * <ul>
 *   <li>{@code /admin/**}    — protected; requires a valid {@code X-Admin-Api-Key} header</li>
 *   <li>{@code /api/**}      — open; rate-limit checks are internal service calls</li>
 *   <li>{@code /actuator/**} — open; health probes from Kubernetes / load balancers</li>
 * </ul>
 *
 * <h2>Why a custom filter instead of HTTP Basic?</h2>
 * <p>API key authentication via a single header is the standard approach for
 * machine-to-machine admin APIs. It avoids the overhead of session management,
 * CSRF tokens, and Basic auth encoding, and maps directly to secret rotation
 * in vault/Kubernetes secrets.
 *
 * <h2>Sessions and CSRF</h2>
 * <p>The API is fully stateless — no sessions, no CSRF needed.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The expected API key value, injected from {@code application.yaml}.
     * In production, override this via an environment variable or Kubernetes secret:
     * {@code ADMIN_API_KEY=<secret>}
     */
    @Value("${admin.api-key}")
    private String adminApiKey;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Stateless API — no sessions, no CSRF
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Route-level authorisation rules
            .authorizeHttpRequests(auth -> auth
                // Admin routes require the ADMIN role (granted by the filter on valid key)
                .requestMatchers("/admin/**").hasRole("ADMIN")
                // Everything else is open (public API + actuator)
                .anyRequest().permitAll()
            )

            // Register our API-key filter BEFORE the standard username/password filter
            .addFilterBefore(
                new AdminApiKeyFilter(adminApiKey),
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }
}
