import { expect, test } from '@playwright/test';
import { stationHandlers } from '../src/features/stations/stationFixtures';

test('PRD 상세·이력은 백엔드 API를 조회하고 DEV 샘플로 대체하지 않는다', async ({ page }) => {
  let detailRequests = 0;
  let historyRequests = 0;
  await page.route('**/api/stations/104', async route => {
    detailRequests++;
    const json = await stationHandlers['GET stations/104']({ path: 'stations/104' });
    await route.fulfill({ json });
  });
  await page.route('**/api/chargers/10401/history?*', async route => {
    historyRequests++;
    const url = new URL(route.request().url());
    expect(url.searchParams.has('from')).toBe(true);
    expect(url.searchParams.has('to')).toBe(true);
    const json = await stationHandlers['GET chargers/10401/history']({ path: 'chargers/10401/history', query: Object.fromEntries(url.searchParams) });
    await route.fulfill({ json });
  });
  await page.goto('/stations/104');
  await expect(page.getByRole('heading', { name: '안성휴게소(부산)' })).toBeVisible();
  expect(detailRequests).toBeGreaterThan(0);
  await expect(page.getByLabel('DEV 환경')).toHaveCount(0);
  await page.getByRole('link', { name: '상태 이력 보기' }).first().click();
  await expect(page.getByRole('heading', { name: '충전기 상태 이력' })).toBeVisible();
  await expect(page.getByText('상태 API 수집').first()).toBeVisible();
  expect(historyRequests).toBeGreaterThan(0);
});
