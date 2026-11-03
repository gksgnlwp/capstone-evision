import { expect, test } from '@playwright/test';
import { sampleRoutes } from '../src/features/search/searchFixtures';
import { recommendationHandlers } from '../src/features/recommendations/recommendationFixtures';

test('PRD 추천은 실제 API 경로·필수 쿼리를 사용하고 응답을 표시한다', async ({ context, page }) => {
  await context.grantPermissions(['geolocation']);
  await context.setGeolocation({ latitude: 37.23, longitude: 127.10 });
  await page.route('**/api/routes', route => route.fulfill({ json: { routes: sampleRoutes } }));
  let requested = false;
  await page.route('**/api/recommendations?*', async route => {
    const url = new URL(route.request().url());
    expect(url.searchParams.get('routeId')).toBe('1');
    expect(url.searchParams.get('remainingRangeKm')).toBe('150');
    expect(url.searchParams.get('lat')).toBe('37.23');
    expect(url.searchParams.get('lng')).toBe('127.1');
    requested = true;
    const json = await recommendationHandlers['GET recommendations']({ path: 'recommendations', query: Object.fromEntries(url.searchParams) });
    await route.fulfill({ json });
  });
  await page.goto('/recommendations?routeId=1&direction=하행&remainingRangeKm=150');
  await page.getByRole('button', { name: '현재 위치 확인' }).click();
  await expect(page.getByRole('heading', { name: '추천 상위 5곳' })).toBeVisible();
  expect(requested).toBe(true);
  await expect(page.getByLabel('DEV 환경')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '시연 위치 사용' })).toHaveCount(0);
});
