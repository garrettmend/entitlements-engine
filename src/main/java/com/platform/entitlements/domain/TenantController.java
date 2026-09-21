/** Exposes the first tenant id so the static control-plane UI can initialize itself. */
package com.platform.entitlements.domain;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/tenants")
public class TenantController {

    private final TenantRepository tenantRepository;

    public TenantController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @GetMapping("/first")
    public FirstTenantResponse firstTenant() {
        return tenantRepository.findFirstByOrderByCreatedAtAsc()
                .map(tenant -> new FirstTenantResponse(tenant.getId()))
                .orElseThrow(() -> new IllegalStateException("No tenants found"));
    }

    public record FirstTenantResponse(UUID id) {
    }
}