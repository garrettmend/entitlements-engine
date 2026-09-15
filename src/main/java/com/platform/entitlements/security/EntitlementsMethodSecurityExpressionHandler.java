package com.platform.entitlements.security;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.core.Authentication;
import com.platform.entitlements.tenant.TenantContext;

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
        return TenantContext.isSet() ? TenantContext.getTenantId() : null;
    }
}
