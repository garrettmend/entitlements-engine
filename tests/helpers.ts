import { Page, expect } from "@playwright/test";

/** Opens the control-plane page and waits for the initial tenant to load from the live API. */
export async function openControlPlane(page: Page): Promise<void> {
  await page.goto("/");
  const apiBaseUrl = process.env.API_BASE_URL;
  if (apiBaseUrl) {
    await page.fill("#baseUrl", apiBaseUrl);
  }
  await expect(page.locator("#tenantLoadLine")).toContainText(/loaded/i, { timeout: 15000 });
}

/** Sets the active tenant's subscription tier via the admin endpoint and waits for confirmation. */
export async function setTenantTier(page: Page, tier: "FREE" | "PRO" | "ENTERPRISE"): Promise<void> {
  await page.selectOption("#tierSelect", tier);
  await page.click("#updateTierBtn");
  await expect(page.locator("#tierResult")).toContainText("204", { timeout: 10000 });
}

/** Clicks "Get random tenant" and waits for a fresh tenant id to load, retrying if it repeats the current one. */
export async function switchToADifferentTenant(page: Page): Promise<string | null> {
  const previousTenant = await page.inputValue("#tenantId");
  for (let attempt = 0; attempt < 5; attempt++) {
    await page.click("#randomTenantBtn");
    await expect(page.locator("#tenantLoadLine")).toContainText("Random tenant", { timeout: 15000 });
    const nextTenant = await page.inputValue("#tenantId");
    if (nextTenant !== previousTenant) return nextTenant;
  }
  return null; // only one tenant seeded — caller should skip tenant-diversity assertions
}
