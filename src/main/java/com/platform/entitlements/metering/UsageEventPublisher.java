package com.platform.entitlements.metering;

public interface UsageEventPublisher {
    void publish(MeteringEventRecorded event);
}
