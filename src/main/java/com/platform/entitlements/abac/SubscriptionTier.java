package com.platform.entitlements.abac;

/**
 * Ordinal order matters here: FREE < PRO < ENTERPRISE, used by
 * atLeast() for "does this tenant meet the MINIMUM tier a feature
 * requires" checks. Keep new tiers inserted in the correct rank order,
 * not appended at the end, if you ever add one between existing tiers.
 */
public enum SubscriptionTier {
    FREE,
    PRO,
    ENTERPRISE;

    public boolean atLeast(SubscriptionTier required) {
        return this.ordinal() >= required.ordinal();
    }

    public static SubscriptionTier fromString(String value) {
        try {
            return SubscriptionTier.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown subscription tier: " + value, e);
        }
    }
}
