package com.platform.entitlements.security;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

public class EntitlementsMethodSecurityExpressionHandler extends DefaultMethodSecurityExpressionHandler {

    private static final String CUSTOM_TENANT_CLAIM = "custom:tenant_id";
    private static final String TENANT_CLAIM = "tenant_id";

    @Override
    protected MethodSecurityExpressionOperations createSecurityExpressionRoot(
            Authentication authentication, MethodInvocation invocation) {

        EntitlementsExpressionRoot root = new EntitlementsExpressionRoot(authentication, extractTenantId(authentication));
        root.setPermissionEvaluator(getPermissionEvaluator());
        root.setTrustResolver(getTrustResolver());
        root.setRoleHierarchy(getRoleHierarchy());
        root.setDefaultRolePrefix(getDefaultRolePrefix());
        return root;
    }

    private String extractTenantId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            // Deliberately return null rather than throwing here: an
            // unauthenticated call should already have been rejected
            // earlier in the filter chain (see SecurityConfig), so reaching
            // this method at all with no JWT is itself a sign something
            // upstream is misconfigured. Returning null lets
            // EntitlementsService.canAccess() fail via its own
            // UUID.fromString(null) NPE with a clear stack trace pointing
            // here, rather than this class silently guessing a tenant.
            return null;
        }
        Object claim = jwt.getClaims().get(CUSTOM_TENANT_CLAIM);
        if (claim == null) {
            claim = jwt.getClaims().get(TENANT_CLAIM);
        }
        return claim == null ? null : claim.toString();
    }
}
