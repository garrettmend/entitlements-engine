/** Defines the transport abstraction used to publish recorded usage events externally. */
package com.platform.entitlements.metering;

public interface UsageEventPublisher {
    void publish(MeteringEventRecorded event);
}
