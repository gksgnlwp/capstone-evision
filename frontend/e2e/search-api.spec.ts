import { expect, test } from '@playwright/test';

// StationQueryControllerTest의 노선·검색 응답 필드와 null 정책을 따르는 계약 사례.
const backendRoutes = [{ routeId: 1, routeNo: '10', routeName: '경부선', directions: ['상행', '하행'], restAreaStations: 1, icStations: 0 }];
const backendStation = {
  stationId: 1, statId: 'ST000001', name: '천안휴게소 충전소', busiId: 'ME', lat: 36.8, lng: 127.2,
  accesses: [{ accessType: 'REST_AREA', restAreaId: 1, restAreaName: '천안', direction: '상행',
    icId: null, icName: null, facilityType: null, routeId: 1, routeNo: '10', routeName: '경부선', detourKm: null }],
  fastChargerCount: 5, availableCount: 1, chargingCount: 1, unavailableCount: 1, unknownCount: 2,
  latestStatusUpdatedAt: '2026-09-28T10:05:00',
};

test('PRD는 DEV 표시 없이 백엔드를 요청하고 응답을 반영한다', async ({ page }) => {
  await page.route('**/api/routes', route => route.fulfill({ json: { routes: backendRoutes } }));
  let search = '';
  await page.route('**/api/stations?*', route => {
    search = route.request().url();
    return route.fulfill({ json: { count: 1, limit: 100, truncated: false, stations: [backendStation] } });
  });
  await page.goto('/route?routeId=1&direction=상행&chargerType=04');
  await expect(page.getByRole('link', { name: '천안휴게소 충전소 상세 보기' })).toBeVisible();
  expect(new URL(search).searchParams.get('direction')).toBe('상행');
  expect(new URL(search).searchParams.get('chargerType')).toBe('04');
  await expect(page.getByText(/예시 충전 상태/)).toHaveCount(0);
  await expect(page.getByLabel('DEV 환경', { exact: true })).toHaveCount(0);
});

test('PRD 요청 실패는 더미 데이터로 대체하지 않고 재시도한다', async ({ page }) => {
  await page.route('**/api/routes', route => route.fulfill({ json: { routes: backendRoutes } }));
  let failed = true;
  await page.route('**/api/stations?*', route => failed ? route.fulfill({ status: 500, body: 'server error' }) : route.fulfill({ json: { count: 0, limit: 100, truncated: false, stations: [] } }));
  await page.goto('/route?routeId=1');
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.locator('.station-card')).toHaveCount(0);
  failed = false;
  await page.getByRole('button', { name: '다시 시도' }).click();
  await expect(page.getByRole('heading', { name: '조건에 맞는 충전소가 없어요' })).toBeVisible();
});

test('PRD 지도 SDK가 없어도 주변 충전소 목록을 조회한다', async ({ page }) => {
  let search = '';
  await page.route('**/api/stations?*', route => {
    search = route.request().url();
    return route.fulfill({ json: { count: 1, limit: 500, truncated: false, stations: [backendStation] } });
  });
  await page.goto('/map');
  await expect(page.getByRole('heading', { name: '카카오맵을 준비하고 있어요' })).toBeVisible();
  await expect(page.getByRole('link', { name: '천안휴게소 충전소 상세 보기' })).toBeVisible();
  const query = new URL(search).searchParams;
  expect(['minLat', 'minLng', 'maxLat', 'maxLng'].every(key => query.has(key))).toBe(true);
});
