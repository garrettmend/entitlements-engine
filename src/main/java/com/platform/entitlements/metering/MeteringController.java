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
@RestController
@RequestMapping("/metering/events")
public class MeteringController {

    private final MeteringService meteringService;

    public MeteringController(MeteringService meteringService) {
        this.meteringService = meteringService;
    }

    @PostMapping
    public ResponseEntity<MeteringEventResponse> recordUsage(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RecordUsageRequest request) {

        if (idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        MeteringEventResponse response = meteringService.recordUsage(idempotencyKey, request);

        // 201 for a genuinely new event, 200 for a replay of a known one —
        // callers that care can also just check the `replayed` field, but
        // the status code alone follows the convention most idempotent
        // APIs use (Stripe, for one) so tooling that only looks at status
        // codes still behaves sensibly.
        HttpStatus status = response.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<String> handleConflict(IdempotencyKeyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
