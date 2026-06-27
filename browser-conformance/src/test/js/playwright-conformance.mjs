import { test, expect } from "@playwright/test";

test("generated browser app passes Fluxzero conformance", async ({ page }) => {
  await page.goto(process.env.FLUXZERO_BROWSER_CONFORMANCE_URL ?? "http://127.0.0.1:8765/");
  const report = await page.evaluate(async () => window.fluxzeroConformance.runAll());
  expect(report.failed).toBe(0);
  expect(report.passed).toBeGreaterThan(0);
  expect(report.featureCoverage["handler.command"]).toBeTruthy();
  expect(report.featureCoverage["web.params"]).toBeTruthy();
});
