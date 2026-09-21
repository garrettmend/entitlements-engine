import { test, expect } from "@playwright/test";
import { openControlPlane, setTenantTier, switchToADifferentTenant } from "./helpers";

// Proves GET /reports/{id} fails closed: guessing another tenant's row id
// 404s instead of leaking whether the row exists.
test.describe("Cross-tenant report lookup fails closed", () => {
  test.beforeEach(async ({ page }) => {
    await openControlPlane(page);
  });

  test("a report created under tenant A 404s when looked up as tenant B", async ({ page }) => {
    await setTenantTier(page, "ENTERPRISE");
    await page.click("#tryCreateBtn");
    await expect(page.locator("#createResult")).toContainText("201", { timeout: 10000 });

    await page.click("#loadReportsBtn");
    await page.locator("#reportsTable tbody tr td[data-full-id]").first().click();
    const fullReportId = await page.inputValue("#reportIdInput");
    expect(fullReportId).toMatch(/^[0-9a-f-]{36}$/i);

    // Confirm it's visible to the tenant that created it first.
    await page.click("#lookupReportBtn");
    await expect(page.locator("#reportLookupResult")).toContainText("200", { timeout: 10000 });

    const otherTenant = await switchToADifferentTenant(page);
    test.skip(otherTenant === null, "Only one tenant is seeded in this database.");

    await page.click("#lookupReportBtn");
    await expect(page.locator("#reportLookupResult")).toContainText("404", { timeout: 10000 });
  });
});
