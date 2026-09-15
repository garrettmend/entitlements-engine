package com.platform.entitlements.abac;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.DeleteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure cache-aside read/write against DynamoDB — no knowledge of Postgres
 * or what to do on a miss. That orchestration (fall back to the source of
 * truth, repopulate on miss) lives in EntitlementsService instead, so the
 * SAME fallback code path runs whether this class or the in-memory test
 * double is the active TenantEntitlementsCache — a miss is just a miss,
 * regardless of which cache implementation produced it.
 *
 * WRITE PATH (put/evict): called by TenantAdminService immediately when a
 * tenant's tier changes, so a downgrade takes effect on the very next
 * request rather than waiting up to TTL_SECONDS for the stale cache entry
 * to expire naturally. This is the answer to "what happens when a tenant
 * downgrades mid-session" — TTL alone would mean a downgraded tenant keeps
 * Enterprise-only access for however long the TTL window is; write-through
 * closes that gap to effectively zero.
 */
@Component
@Profile("!test")
public class DynamoDbTenantEntitlementsCache implements TenantEntitlementsCache {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final DynamoDbTable<TenantEntitlementsItem> table;

    public DynamoDbTenantEntitlementsCache(
            DynamoDbEnhancedClient enhancedClient,
            @Value("${aws.dynamodb.entitlements-table}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(TenantEntitlementsItem.class));
    }

    @Override
    public Optional<SubscriptionTier> get(UUID tenantId) {
        TenantEntitlementsItem item = table.getItem(keyItem(tenantId));
        return item == null
                ? Optional.empty()
                : Optional.of(SubscriptionTier.fromString(item.getSubscriptionTier()));
    }

    @Override
    public void put(UUID tenantId, SubscriptionTier tier) {
        TenantEntitlementsItem item = new TenantEntitlementsItem();
        item.setTenantId(tenantId.toString());
        item.setSubscriptionTier(tier.name());
        item.setExpiresAt(Instant.now().plus(TTL).getEpochSecond());
        table.putItem(PutItemEnhancedRequest.builder(TenantEntitlementsItem.class).item(item).build());
    }

    @Override
    public void evict(UUID tenantId) {
        table.deleteItem(DeleteItemEnhancedRequest.builder().key(k -> k.partitionValue(tenantId.toString())).build());
    }

    private TenantEntitlementsItem keyItem(UUID tenantId) {
        TenantEntitlementsItem key = new TenantEntitlementsItem();
        key.setTenantId(tenantId.toString());
        return key;
    }
}
