/** Defines the static permission rules that map permission codes to required tiers and user groups. */
package com.platform.entitlements.abac;

import java.util.Map;

/**
 * Declares, per permission code, what it takes to be granted it: a minimum
 * tenant subscription tier (nullable — no tier requirement), a required
 * Cognito group membership (nullable — no group requirement), or both.
 *
 * This is a static in-code map for the demo. In a real system this table
 * would live in Postgres or DynamoDB itself, editable by an internal admin
 * tool, so product/sales can gate a new feature behind a tier without a
 * code deploy. Swapping the static map below for a DB-backed lookup
 * (cached the same way TenantEntitlementsCache caches tier) is the natural
 * next step — the calling code in EntitlementsService wouldn't need to
 * change at all, since it only depends on this class's forCode() method.
 */
public final class PermissionRule {

    // The blueprint's own example: creating a report requires BOTH the
    // tenant being on Enterprise AND the specific user having been granted
    // the Report_Writer group/role. Two independent attributes, ANDed.
    private static final Map<String, PermissionRule> RULES = Map.of(
            "EXPORT_DATA", new PermissionRule(SubscriptionTier.PRO, null),
            "CREATE_REPORT", new PermissionRule(SubscriptionTier.ENTERPRISE, "REPORT_WRITER")
    );

    private final SubscriptionTier requiredTier;
    private final String requiredGroup;

    private PermissionRule(SubscriptionTier requiredTier, String requiredGroup) {
        this.requiredTier = requiredTier;
        this.requiredGroup = requiredGroup;
    }

    public SubscriptionTier requiredTier() {
        return requiredTier;
    }

    public String requiredGroup() {
        return requiredGroup;
    }

    public static PermissionRule forCode(String code) {
        PermissionRule rule = RULES.get(code);
        if (rule == null) {
            throw new IllegalArgumentException(
                    "No PermissionRule registered for code '" + code + "' — " +
                    "did you forget to add it to PermissionRule.RULES, or is this a typo " +
                    "in an @PreAuthorize annotation? Failing loudly here on purpose: a typo'd " +
                    "permission code that silently evaluated to \"deny\" would be safe but " +
                    "confusing to debug; one that silently evaluated to \"allow\" would be a " +
                    "security hole. Throwing makes the mistake impossible to miss.");
        }
        return rule;
    }
}
