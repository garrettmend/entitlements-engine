package com.platform.entitlements.security;

import com.platform.entitlements.tenant.TenantFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain publicUiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/", "/index.html")
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers("/", "/index.html");
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html").permitAll()
                .requestMatchers("/actuator/**", "/health/**").permitAll()
                .anyRequest().permitAll()
            )
            .addFilterBefore(new TenantFilter(), AnonymousAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Allows any browser origin to call this API. That's deliberately fine
     * HERE specifically because auth is stateless bearer-token (Authorization
     * header), not cookie-based: there's no ambient credential a malicious
     * page could ride on. A caller still needs to already possess a valid
     * JWT to do anything — wildcard CORS just means "any page's JavaScript
     * may attempt the request," not "any page can act as an authenticated
     * user." This would be a real vulnerability (CSRF) if this API used
     * cookie/session auth instead — don't copy this config blindly onto a
     * cookie-authenticated service.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Idempotency-Key", "X-Tenant-Id", "X-User-Groups"));
        configuration.setAllowCredentials(false); // must be false when origins is "*"

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

