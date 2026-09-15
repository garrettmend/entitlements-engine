package com.platform.entitlements.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs AFTER Spring Security's JWT authentication filter (see SecurityConfig,
 * where this is registered with addFilterAfter(...) against the bearer token
 * filter). By the time this filter executes, SecurityContextHolder already
 * holds a validated, signature-checked JWT — we are just reading a claim off
 * a token we already trust, not doing any auth ourselves.
 *
 * We deliberately do NOT trust a tenant id from a header, query param, or
 * request body: those are attacker-controlled. The tenant id MUST come from
 * inside the signed JWT claims (issued by Cognito at login, tied to the
 * user's actual tenant membership), so there is no way for a caller to claim
 * to be a different tenant than the one they authenticated as.
 */
public class TenantFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);
    private static final String TENANT_CLAIM = "custom:tenant_id"; // Cognito custom attribute convention

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        try {
            String tenantId = extractTenantId();
            if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
            } else if (requiresTenant(request)) {
                // Authenticated but no tenant claim on the token — this is a
                // misconfigured user/token, not a missing-auth case (Spring
                // Security would have already rejected an unauthenticated
                // request). Fail closed rather than letting the request
                // through with no tenant context.
                log.warn("Authenticated request to {} has no tenant_id claim", request.getRequestURI());
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "No tenant associated with this token");
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            // Always clear — this thread will be reused by the servlet
            // container's thread pool for a future, unrelated request.
            TenantContext.clear();
        }
    }

    private String extractTenantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        Object claim = jwt.getClaims().get(TENANT_CLAIM);
        return claim == null ? null : claim.toString();
    }

    private boolean requiresTenant(HttpServletRequest request) {
        // Public/health endpoints and unauthenticated paths don't need a
        // tenant. Keep this in sync with SecurityConfig's permitAll() list.
        String path = request.getRequestURI();
        return !(path.equals("/") || path.equals("/index.html")
            || path.startsWith("/actuator") || path.startsWith("/health"));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/") || path.equals("/index.html")
            || path.startsWith("/actuator") || path.startsWith("/health");
    }
}
