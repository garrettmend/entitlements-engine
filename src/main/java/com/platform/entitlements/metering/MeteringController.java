/** Exposes the internal HTTP endpoint that records tenant usage with idempotency protection. */
package com.platform.entitlements.metering;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.platform.entitlements.metering.MeteringDtos.MeteringEventResponse;
import static com.platform.entitlements.metering.MeteringDtos.RecordUsageRequest;

/**
 * Internal endpoint — meant to be called by OTHER services in the platform
 * reporting usage on behalf of a tenant (e.g. "the reports service just
 * generated a report for tenant X"), not by end-user browsers. In a real
 * deployment this would sit behind service-to-service auth (mTLS, an
 * internal-only JWT audience, etc.) rather than the same public-facing
 * Cognito user tokens as /reports — that's a deployment/network-topology
 * concern outside this repo's scope, but worth flagging so it isn't
 * accidentally exposed the same way as end-user endpoints.
 */
// 1. THE INTERNAL BACKDOOR
// This controller is intentionally not for end-users. It is a private 
// channel for your other microservices to report usage data securely.
@RestController
@RequestMapping("/metering/events")
public class MeteringController {

    private final MeteringService meteringService;

    public MeteringController(MeteringService meteringService) {
        this.meteringService = meteringService;
    }

    @PostMapping
    public ResponseEntity<MeteringEventResponse> recordUsage(
            // 2. THE IDEMPOTENCY KEY (Safety against Double-Billing)
            // The calling service generates a unique transaction ID. If the network
            // crashes and it has to retry, it sends the exact same key so we don't
            // accidentally bill the tenant twice for the same event.
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RecordUsageRequest request) {

        if (idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Passes the key and payload to the service we looked at earlier
        MeteringEventResponse response = meteringService.recordUsage(idempotencyKey, request);

        // 3. SMART STATUS CODES
        // 201 for a genuinely new event, 200 for a replay of a known one —
        // callers that care can also just check the `replayed` field, but
        // the status code alone follows the convention most idempotent
        // APIs use (Stripe, for one) so tooling that only looks at status
        // codes still behaves sensibly.
        HttpStatus status = response.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    // 4. CONFLICT RESOLUTION
    // If the service detects that the caller is trying to use an old Idempotency-Key 
    // for a completely different request payload, it throws this exception.
    // This handler catches it and returns a 409 CONFLICT to the caller.
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<String> handleConflict(IdempotencyKeyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}