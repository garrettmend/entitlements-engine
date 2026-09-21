import { test, expect } from "@playwright/test";
import { openControlPlane, setTenantTier } from "./helpers";

// Proves the "Run smoke test" button drives real API calls end to end and
// logs a pass/fail line per check to the browser console.
test.describe("Browser console smoke test button", () => {
  test.beforeEach(async ({ page }) => {
    await openControlPlane(page);
  });

  test.afterAll(async ({ browser }) => {
    const page = await browser.newPage();
    await openControlPlane(page);
    await setTenantTier(page, "ENTERPRISE");
    await page.close();
  });

  test("clicking it logs PASS/FAIL lines and a summary count to the console", async ({ page }) => {
    const consoleLines: string[] = [];
    page.on("console", (msg) => consoleLines.push(msg.text()));

    await page.click("#runSmokeTestBtn");
    await expect(page.locator("#runSmokeTestBtn")).toBeEnabled({ timeout: 30000 });

    const summaryLine = consoleLines.find((line) => /\d+ \/ \d+ checks passed/.test(line));
    expect(summaryLine).toBeTruthy();

    const failLines = consoleLines.filter((line) => line.includes("FAIL"));
    expect(failLines).toEqual([]);
  });
});
