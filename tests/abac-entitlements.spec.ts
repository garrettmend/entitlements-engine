import { test, expect } from "@playwright/test";
import { openControlPlane, setTenantTier } from "./helpers";

// Proves the @PreAuthorize ABAC checks are driven by the tenant's real, live
// subscription tier (Postgres + entitlements cache) — not a UI-side simulation.
test.describe("ABAC entitlements react to live tier changes", () => {
  test.beforeEach(async ({ page }) => {
    await openControlPlane(page);
  });

  // These tests flip the shared demo tenant's tier as a side effect; leave it
  // on ENTERPRISE afterward so the "everything succeeds" demo default holds.
  test.afterAll(async ({ browser }) => {
    const page = await browser.newPage();
    await openControlPlane(page);
    await setTenantTier(page, "ENTERPRISE");
    await page.close();
  });

  test("Enterprise tier allows report creation; Free tier denies it", async ({ page }) => {
    await setTenantTier(page, "ENTERPRISE");
    await page.click("#tryCreateBtn");
    await expect(page.locator("#createResult")).toContainText("201", { timeout: 10000 });

    await setTenantTier(page, "FREE");
    await page.click("#tryCreateBtn");
    await expect(page.locator("#createResult")).toContainText("403", { timeout: 10000 });
  });

  test("Pro tier allows data export; Free tier denies it", async ({ page }) => {
    await setTenantTier(page, "PRO");
    await page.click("#tryExportBtn");
    await expect(page.locator("#exportResult")).toContainText("200", { timeout: 10000 });

    await setTenantTier(page, "FREE");
    await page.click("#tryExportBtn");
    await expect(page.locator("#exportResult")).toContainText("403", { timeout: 10000 });
  });

  test("Enterprise tier alone is not enough without also restoring it after a Free downgrade", async ({ page }) => {
    // Sanity check that PRO (below CREATE_REPORT's ENTERPRISE requirement)
    // still isn't sufficient for report creation, even though it satisfies export.
    await setTenantTier(page, "PRO");
    await page.click("#tryCreateBtn");
    await expect(page.locator("#createResult")).toContainText("403", { timeout: 10000 });
  });
});
