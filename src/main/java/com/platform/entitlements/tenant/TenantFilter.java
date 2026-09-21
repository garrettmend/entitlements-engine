/** Loads tenant identity for protected HTTP requests and always clears it afterward. */
package com.platform.entitlements.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Loads the tenant context from the explicit X-Tenant-Id API header.
 * Authentication is intentionally outside this demo API's scope; callers
 * must treat this endpoint as trusted or put it behind an API gateway.
 */
public class TenantFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);
    private static final String CUSTOM_TENANT_CLAIM = "custom:tenant_id";
    private static final String TENANT_CLAIM = "tenant_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        try {
            String tenantId = request.getHeader("X-Tenant-Id");
            if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
            } else if (requiresTenant(request)) {
                log.warn("Request to {} has no X-Tenant-Id header", request.getRequestURI());
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "X-Tenant-Id header is required");
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            // Always clear — this thread will be reused by the servlet
            // container's thread pool for a future, unrelated request.
            TenantContext.clear();
        }
    }

    private boolean requiresTenant(HttpServletRequest request) {
        // Public/health endpoints don't need a tenant.
        String path = request.getRequestURI();
        return !(path.equals("/") || path.equals("/index.html") || path.equals("/tenants/first")
            || path.equals("/tenants/random")
            || path.startsWith("/actuator") || path.startsWith("/health"));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/") || path.equals("/index.html") || path.equals("/tenants/first")
            || path.equals("/tenants/random")
            || path.startsWith("/actuator") || path.startsWith("/health");
    }
}
