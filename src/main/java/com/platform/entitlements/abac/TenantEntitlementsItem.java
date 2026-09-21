/** Maps a cached tenant subscription tier and its expiration timestamp to a DynamoDB item. */
package com.platform.entitlements.abac;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * Maps to the "tenant-entitlements" DynamoDB table (see application.yml).
 * `expiresAt` is a plain epoch-seconds attribute; the DynamoDB table itself
 * needs its native TTL feature enabled ON this attribute name (that's
 * infrastructure config via CDK/Terraform/console, not something the SDK
 * sets up — out of scope for this repo, but the field is useless without it).
 * TTL is deliberately a BACKSTOP, not the primary invalidation mechanism —
 * see TenantAdminService for the write-through path that keeps the cache
 * correct immediately on a tier change, rather than up to TTL-seconds stale.
 */
@DynamoDbBean
public class TenantEntitlementsItem {

    private String tenantId;
    private String subscriptionTier;
    private Long expiresAt;

    @DynamoDbPartitionKey
    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getSubscriptionTier() {
        return subscriptionTier;
    }

    public void setSubscriptionTier(String subscriptionTier) {
        this.subscriptionTier = subscriptionTier;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
