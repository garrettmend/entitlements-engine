import { test, expect } from "@playwright/test";
import { openControlPlane } from "./helpers";

// Proves POST /metering/events is idempotent on Idempotency-Key — a retried
// request returns the original row instead of creating a duplicate.
test.describe("Usage metering idempotency", () => {
  test.beforeEach(async ({ page }) => {
    await openControlPlane(page);
    await page.click("#clearLogBtn");
  });

  test("resending the same Idempotency-Key replays the original event", async ({ page }) => {
    await page.click("#sendEventBtn");
    await expect(page.locator("#eventsTable tbody tr")).toHaveCount(1, { timeout: 10000 });
    await expect(page.locator("#eventsTable tbody tr").first().locator(".badge.new")).toBeVisible();
    const originalEventId = await page.locator("#eventsTable tbody tr").first().locator("td").nth(4).textContent();

    await page.click("#retryEventBtn");
    // A replay flashes the existing row rather than appending a new one.
    await expect(page.locator("#eventsTable tbody tr")).toHaveCount(1, { timeout: 10000 });
    const replayedEventId = await page.locator("#eventsTable tbody tr").first().locator("td").nth(4).textContent();
    expect(replayedEventId).toBe(originalEventId);
  });

  test("a fresh Idempotency-Key creates a genuinely new event", async ({ page }) => {
    await page.click("#sendEventBtn");
    await expect(page.locator("#eventsTable tbody tr")).toHaveCount(1, { timeout: 10000 });

    await page.click("#regenKeyBtn");
    await page.click("#sendEventBtn");
    await expect(page.locator("#eventsTable tbody tr")).toHaveCount(2, { timeout: 10000 });
    await expect(page.locator("#eventsTable tbody tr").first().locator(".badge.new")).toBeVisible();
  });
});
