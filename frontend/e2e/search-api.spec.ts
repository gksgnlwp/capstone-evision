import { expect, test } from '@playwright/test';
import { sampleRoutes, sampleStations } from '../src/features/search/searchFixtures';

test('PRD는 DEV 표시 없이 백엔드를 요청하고 응답을 반영한다', async ({ page }) => {
  await page.route('**/api/routes', route => route.fulfill({ json: { routes: sampleRoutes } }));
  let search = '';
  await page.route('**/api/stations?*', route => {
    search = route.request().url();
    return route.fulfill({ json: { count: 1, limit: 100, truncated: false, stations: [{ ...sampleStations[0], name: '계약 테스트 충전소' }] } });
  });
  await page.goto('/route?routeId=1&direction=하행&chargerType=04');
  await expect(page.getByRole('link', { name: '계약 테스트 충전소 상세 보기' })).toBeVisible();
  expect(new URL(search).searchParams.get('direction')).toBe('하행');
  expect(new URL(search).searchParams.get('chargerType')).toBe('04');
  await expect(page.getByText(/예시 충전 상태/)).toHaveCount(0);
  await expect(page.getByLabel('DEV 환경', { exact: true })).toHaveCount(0);
});

test('PRD 요청 실패는 더미 데이터로 대체하지 않고 재시도한다', async ({ page }) => {
  await page.route('**/api/routes', route => route.fulfill({ json: { routes: sampleRoutes } }));
  let failed = true;
  await page.route('**/api/stations?*', route => failed ? route.fulfill({ status: 500, body: 'server error' }) : route.fulfill({ json: { count: 0, limit: 100, truncated: false, stations: [] } }));
  await page.goto('/route?routeId=1');
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.locator('.station-card')).toHaveCount(0);
  failed = false;
  await page.getByRole('button', { name: '다시 시도' }).click();
  await expect(page.getByRole('heading', { name: '조건에 맞는 충전소가 없어요' })).toBeVisible();
});
