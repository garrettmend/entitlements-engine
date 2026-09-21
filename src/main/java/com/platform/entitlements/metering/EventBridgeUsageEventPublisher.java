/** Publishes validated metering events to AWS EventBridge and detects per-entry delivery failures. */
package com.platform.entitlements.metering;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;

/**
 * Routes validated, deduplicated usage events onto EventBridge. Downstream,
 * a rule on the target bus would fan this out to (for example) an S3
 * bucket / Firehose for end-of-month invoice generation — that pipeline is
 * infrastructure config (CDK/Terraform), not application code, so it's out
 * of scope for this repo, but the event shape below is what it consumes.
 */

// 1. SPRING BEAN & PROFILE SCOPING
// Scoped with @Profile("!test") so Spring skips loading this bean during test runs,
// preventing accidental AWS calls or needing live AWS credentials in unit tests.
@Component
@Profile("!test")
public class EventBridgeUsageEventPublisher implements UsageEventPublisher {

    private final EventBridgeClient eventBridgeClient;
    private final String eventBusName;

    // 2. CONSTRUCTOR INJECTION & CONFIGURATION
    // Injecting the AWS v2 SDK client and reading the event bus name from application properties.
    public EventBridgeUsageEventPublisher(
            EventBridgeClient eventBridgeClient,
            @Value("${aws.eventbridge.event-bus-name}") String eventBusName) {
        this.eventBridgeClient = eventBridgeClient;
        this.eventBusName = eventBusName;
    }

    @Override
    public void publish(MeteringEventRecorded event) {
        // 3. JSON PAYLOAD MARSHALLING
        // Formatting the event data into a compact JSON payload using Java Text Blocks.
        String detail = """
                {"eventId":"%s","tenantId":"%s","eventType":"%s","quantity":%s}
                """.formatted(event.eventId(), event.tenantId(), event.eventType(), event.quantity())
                .strip();

        // 4. AWS EVENT STRUCTURE
        // 'source' identifies the subsystem publishing the event.
        // 'detailType' serves as the event type filter key for downstream EventBridge Rules.
        PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(eventBusName)
                .source("entitlements-engine.metering")
                .detailType("UsageEventRecorded")
                .detail(detail)
                .build();

        // 5. AWS SDK CALL
        PutEventsResponse response = eventBridgeClient.putEvents(
                PutEventsRequest.builder().entries(entry).build());

        // 6. CRITICAL: HANDLING AWS PARTIAL FAILURES
        // PutEvents returns HTTP 200 even if individual entries fail (e.g., policy or rate limit issues).
        // If failedEntryCount > 0, we MUST throw an exception so the calling 
        // MeteringEventPublishListener catches it and leaves 'publishedAt' NULL for retry.
        if (response.failedEntryCount() != null && response.failedEntryCount() > 0) {
            PutEventsResultEntry failed = response.entries().get(0);
            throw new EventBridgePublishException(
                    "EventBridge rejected entry: " + failed.errorCode() + " - " + failed.errorMessage());
        }
    }

    // Custom runtime exception thrown when EventBridge rejects the event submission.
    public static class EventBridgePublishException extends RuntimeException {
        public EventBridgePublishException(String message) {
            super(message);
        }
    }
}