package com.platform.entitlements.abac;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Internal/operator-facing — in a real deployment this sits behind
 * whatever billing-webhook or internal-admin auth your platform uses, NOT
 * the same end-user Cognito tokens as /reports or /metering/events. No
 * @PreAuthorize is wired here deliberately, as a placeholder for whatever
 * that internal auth mechanism turns out to be — left as a TODO-by-omission
 * rather than a false sense of security via an incomplete check.
 */
@RestController
@RequestMapping("/admin/tenants")
public class TenantAdminController {

    private final TenantAdminService tenantAdminService;

    public TenantAdminController(TenantAdminService tenantAdminService) {
        this.tenantAdminService = tenantAdminService;
    }

    @PatchMapping("/{tenantId}/subscription-tier")
    public ResponseEntity<Void> updateSubscriptionTier(
            @PathVariable UUID tenantId, @RequestBody UpdateTierRequest request) {
        tenantAdminService.updateSubscriptionTier(tenantId, SubscriptionTier.fromString(request.tier()));
        return ResponseEntity.noContent().build();
    }

    public record UpdateTierRequest(String tier) {
    }
}
