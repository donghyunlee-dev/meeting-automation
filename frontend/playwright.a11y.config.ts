import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/accessibility.spec.ts',
  fullyParallel: true,
  timeout: 30_000,
  reporter: 'list',
  outputDir: './test-results/accessibility',
  use: {
    baseURL: 'http://127.0.0.1:4174',
    ...devices['Desktop Chrome'],
    viewport: { width: 390, height: 844 },
  },
  projects: [{ name: 'chromium-a11y', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4174 --strictPort',
    url: 'http://127.0.0.1:4174',
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
