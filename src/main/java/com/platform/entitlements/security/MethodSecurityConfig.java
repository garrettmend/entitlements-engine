/** Enables method-level Spring Security and registers the custom ABAC expression handler. */
package com.platform.entitlements.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableMethodSecurity // prePostEnabled defaults to true, which is what @PreAuthorize needs
public class MethodSecurityConfig {

    /**
     * Spring Security auto-detects a user-defined MethodSecurityExpressionHandler
     * bean when @EnableMethodSecurity is in use — no further wiring needed
     * beyond declaring it. The `static` modifier is Spring Security's own
     * documented recommendation for this bean: it avoids the bean being
     * proxied by other BeanPostProcessors (e.g. for AOP) before method
     * security infrastructure needs it, which can otherwise cause subtle
     * initialization-order failures.
     */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        return new EntitlementsMethodSecurityExpressionHandler();
    }
}
