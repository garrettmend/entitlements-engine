/** Records test publication calls in memory instead of contacting AWS EventBridge. */
package com.platform.entitlements.metering;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Active under the "test" profile in place of EventBridgeUsageEventPublisher
 * (which is @Profile("!test")), so tests never touch real AWS. Records
 * every publish() call so tests can assert on what WOULD have been sent —
 * e.g. "publish was called exactly once, even though the client retried
 * the request three times" (see MeteringPublishAfterCommitIT).
 */
@Component
@Profile("test")
public class NoOpUsageEventPublisher implements UsageEventPublisher {

    private final List<MeteringEventRecorded> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(MeteringEventRecorded event) {
        published.add(event);
    }

    public List<MeteringEventRecorded> published() {
        return published;
    }

    public void clear() {
        published.clear();
    }
}
