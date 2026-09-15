package com.platform.entitlements.security;

import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.core.Authentication;

/**
 * Adds `tenantId` as a plain SpEL variable available in every @PreAuthorize
 * expression, without it needing to be a method parameter — this is what
 * lets you write:
 *
 *     @PreAuthorize("@entitlements.canAccess(tenantId, 'CREATE_REPORT')")
 *
 * on a controller method that has no `tenantId` argument of its own,
 * because `tenantId` here is a property on this root object, populated
 * from the JWT by EntitlementsMethodSecurityExpressionHandler, not a
 * `#tenantId` reference to a method parameter (note: no `#`).
 *
 * HONEST TRADEOFF NOTE: this is meaningfully more machinery than the
 * alternative of just writing `@PreAuthorize("@entitlements.canAccess(...)")`
 * with NO tenantId argument at all, and having EntitlementsService pull the
 * tenant id from TenantContext internally (it's already sitting there,
 * populated by TenantFilter, by the time any @PreAuthorize check runs). For
 * a single permission check per method, that's genuinely simpler and I'd
 * usually reach for it first. The custom expression root earns its keep
 * once you want MULTIPLE derived, request-scoped attributes (tenantId,
 * subscription tier, user role, etc.) available directly as first-class
 * SpEL variables across many annotations without repeating lookup
 * boilerplate in every service method — which is the situation a growing
 * ABAC surface actually tends toward. Built here because that's the pattern
 * the project brief specifically asked to demonstrate.
 */
public class EntitlementsExpressionRoot extends SecurityExpressionRoot implements MethodSecurityExpressionOperations {

    public final String tenantId;

    private Object filterObject;
    private Object returnObject;

    public EntitlementsExpressionRoot(Authentication authentication, String tenantId) {
        super(authentication);
        this.tenantId = tenantId;
    }

    @Override
    public void setFilterObject(Object filterObject) {
        this.filterObject = filterObject;
    }

    @Override
    public Object getFilterObject() {
        return filterObject;
    }

    @Override
    public void setReturnObject(Object returnObject) {
        this.returnObject = returnObject;
    }

    @Override
    public Object getReturnObject() {
        return returnObject;
    }

    @Override
    public Object getThis() {
        return this;
    }
}
