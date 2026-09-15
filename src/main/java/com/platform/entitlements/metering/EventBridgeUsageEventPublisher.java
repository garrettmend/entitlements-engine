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
@Component
@Profile("!test")
public class EventBridgeUsageEventPublisher implements UsageEventPublisher {

    private final EventBridgeClient eventBridgeClient;
    private final String eventBusName;

    public EventBridgeUsageEventPublisher(
            EventBridgeClient eventBridgeClient,
            @Value("${aws.eventbridge.event-bus-name}") String eventBusName) {
        this.eventBridgeClient = eventBridgeClient;
        this.eventBusName = eventBusName;
    }

    @Override
    public void publish(MeteringEventRecorded event) {
        String detail = """
                {"eventId":"%s","tenantId":"%s","eventType":"%s","quantity":%s}
                """.formatted(event.eventId(), event.tenantId(), event.eventType(), event.quantity())
                .strip();

        PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(eventBusName)
                .source("entitlements-engine.metering")
                .detailType("UsageEventRecorded")
                .detail(detail)
                .build();

        PutEventsResponse response = eventBridgeClient.putEvents(
                PutEventsRequest.builder().entries(entry).build());

        // PutEvents does NOT throw on a per-entry failure — a partial
        // failure comes back as a 200 response with FailedEntryCount > 0
        // and an error code/message on the individual entry. Checking only
        // the HTTP-level result (whether the SDK call itself threw) would
        // silently swallow this and the reconciler would never know to retry.
        if (response.failedEntryCount() != null && response.failedEntryCount() > 0) {
            PutEventsResultEntry failed = response.entries().get(0);
            throw new EventBridgePublishException(
                    "EventBridge rejected entry: " + failed.errorCode() + " - " + failed.errorMessage());
        }
    }

    public static class EventBridgePublishException extends RuntimeException {
        public EventBridgePublishException(String message) {
            super(message);
        }
    }
}
