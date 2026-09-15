package com.platform.entitlements.domain;

import com.platform.entitlements.tenant.TenantContext;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportRepository reportRepository;

    /**
     * Returns ONLY the current tenant's reports. There's no tenant filter
     * in this method — it comes for free from RLS. Try hitting this with
     * two different tenants' tokens against the same running instance and
     * seeding data as each; each caller sees only their own rows.
     */
    @GetMapping
    public List<Report> listReports() {
        return reportRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Report> getReport(@PathVariable UUID id) {
        // Also demonstrates the fail-closed property: if another tenant's
        // report id is guessed/enumerated, RLS makes findById() return
        // empty rather than the row, so this correctly 404s instead of
        // leaking existence or content of another tenant's data.
        return reportRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    // The ABAC example from the original design brief: `tenantId` here is
    // NOT a method parameter — it's supplied by our custom
    // EntitlementsExpressionRoot (see the security package), populated
    // from the JWT. canAccess looks up PermissionRule.forCode("CREATE_REPORT"),
    // which requires BOTH the tenant being on ENTERPRISE tier (checked via
    // the DynamoDB-cached, Postgres-backed lookup) AND the calling user
    // holding the REPORT_WRITER Cognito group (checked directly off the
    // JWT). An Enterprise-tenant user without the group is rejected; a
    // Pro-tenant user WITH the group is still rejected — neither attribute
    // alone is sufficient.
    @PreAuthorize("@entitlements.canAccess(tenantId, 'CREATE_REPORT')")
    public Report createReport(@RequestBody CreateReportRequest request, Authentication authentication) {
        Report report = new Report();
        // tenant_id comes from TenantContext (itself derived from the JWT),
        // never from the request body — the WITH CHECK policy in V2 would
        // reject a mismatched value anyway, but we don't even give a caller
        // the chance to try.
        report.setTenantId(UUID.fromString(TenantContext.getTenantId()));
        report.setTitle(request.title());
        report.setBody(request.body());
        report.setCreatedBy(authentication.getName());
        return reportRepository.save(report);
    }

    @GetMapping("/export")
    // Contrast with createReport below: this permission has a tier
    // requirement (PRO or above) but NO required group — any authenticated
    // user at a qualifying tenant can call this, regardless of their own
    // individual permissions. Shows PermissionRule supporting either
    // attribute independently, not just the combined case.
    @PreAuthorize("@entitlements.canAccess(tenantId, 'EXPORT_DATA')")
    public ResponseEntity<String> exportReports() {
        List<Report> reports = reportRepository.findAll();
        StringBuilder csv = new StringBuilder("id,title,created_by,created_at\n");
        for (Report r : reports) {
            csv.append(r.getId()).append(',')
                    .append(r.getTitle().replace(",", " ")).append(',')
                    .append(r.getCreatedBy()).append(',')
                    .append(r.getCreatedAt()).append('\n');
        }
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv")
                .body(csv.toString());
    }

    public record CreateReportRequest(@NotBlank String title, String body) {
    }
}
