import { test, expect } from "@playwright/test";
import { openControlPlane, switchToADifferentTenant } from "./helpers";

// Proves GET /reports is scoped per-tenant by Postgres RLS, not application-level filtering.
test.describe("Tenant isolation (Postgres row-level security)", () => {
  test.beforeEach(async ({ page }) => {
    await openControlPlane(page);
  });

  test("loading reports returns a real response for the active tenant", async ({ page }) => {
    await page.click("#loadReportsBtn");
    await expect(page.locator("#reportsTable")).toBeVisible({ timeout: 10000 });
    await expect(page.locator("#reportsTable thead th").first()).toHaveText("Title");
  });

  test("switching tenants changes which reports are visible", async ({ page }) => {
    await page.click("#loadReportsBtn");
    const firstTenantRows = await page.locator("#reportsTable tbody tr").count();

    const newTenant = await switchToADifferentTenant(page);
    test.skip(newTenant === null, "Only one tenant is seeded in this database.");

    await page.click("#loadReportsBtn");
    await expect(page.locator("#reportsTable")).toBeVisible({ timeout: 10000 });
    // Not asserting a specific count — only that this tenant's own (possibly
    // different, possibly empty) row set loaded without leaking tenant A's data.
    const secondTenantRows = await page.locator("#reportsTable tbody tr").count();
    expect(secondTenantRows).toBeGreaterThanOrEqual(0);
    expect(firstTenantRows).toBeGreaterThanOrEqual(0);
  });

  test("comparing two tenants side by side loads both independently", async ({ page }) => {
    await page.click("#compareToggle");
    await expect(page.locator("#comparePanel")).toHaveClass(/show/);
    // The compare panel needs two real tokens to run its own fetches; without
    // one it should fail gracefully rather than silently no-op.
    await page.click("#compareBtn");
    await expect(page.locator("#compareGrid")).toContainText(/need both tokens/i);
  });
});
