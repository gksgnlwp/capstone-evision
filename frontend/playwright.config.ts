import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  retries: 0,
  use: { baseURL: 'http://127.0.0.1:5183', trace: 'retain-on-failure' },
  webServer: [
    { command: 'npm run dev -- --port 5183 --strictPort', url: 'http://127.0.0.1:5183', env: { VITE_APP_ENV: 'DEV', VITE_KAKAO_MAP_APP_KEY: '' }, reuseExistingServer: !process.env.CI },
    { command: 'npm run dev -- --port 5184 --strictPort', url: 'http://127.0.0.1:5184', env: { VITE_APP_ENV: 'PRD', VITE_KAKAO_MAP_APP_KEY: '' }, reuseExistingServer: !process.env.CI },
  ],
  projects: [
    { name: 'mobile', testIgnore: '**/search-api.spec.ts', use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium' } },
    { name: 'api-contract', testMatch: '**/search-api.spec.ts', use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium', baseURL: 'http://127.0.0.1:5184' } },
  ],
});
