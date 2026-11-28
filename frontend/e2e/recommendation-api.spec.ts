import { expect, test } from '@playwright/test';
import { recommendationHandlers } from '../src/features/recommendations/recommendationFixtures';

const query = 'destination=부산역&destLat=35.1151&destLng=129.0415&remainingRangeKm=150';

test('PRD 추천과 비교가 목적지 좌표로 조회하고 서버가 고른 노선을 표시한다', async ({ context, page }) => {
  await context.grantPermissions(['geolocation']);
  await context.setGeolocation({ latitude: 37.23, longitude: 127.10 });
  let requested = 0;
  await page.route('**/api/stations?*', route => {
    const params = new URL(route.request().url()).searchParams;
    expect(params.get('routeId')).toBe('1');
    expect(params.get('direction')).toBe('하행');
    return route.fulfill({ json: { count: 0, limit: 1, truncated: false, statusCollectedAt: null, stations: [] } });
  });
  await page.route('**/api/recommendations?*', async route => {
    const url = new URL(route.request().url());
    expect(url.searchParams.has('routeId')).toBe(false);
    expect(url.searchParams.has('direction')).toBe(false);
    expect(url.searchParams.get('destLat')).toBe('35.1151');
    expect(url.searchParams.get('destLng')).toBe('129.0415');
    expect(url.searchParams.get('remainingRangeKm')).toBe('150');
    expect(url.searchParams.get('lat')).toBe('37.23');
    expect(url.searchParams.get('lng')).toBe('127.1');
    requested++;
    const json = await recommendationHandlers['GET recommendations']({ path: 'recommendations', query: Object.fromEntries(url.searchParams) });
    await route.fulfill({ json });
  });
  await page.goto(`/recommendations?${query}`);
  await page.getByRole('button', { name: '현재 위치', exact: true }).click();
  await expect(page.getByRole('heading', { name: '추천 상위 5곳' })).toBeVisible();
  await expect(page.getByText('추천 노선 경부선 · 하행')).toBeVisible();
  await expect(page.getByLabel('DEV 환경')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '시연 위치 사용' })).toHaveCount(0);
  await page.getByRole('checkbox', { name: '안성휴게소(부산) 비교 선택' }).check();
  await page.getByRole('checkbox', { name: '오산IC 인근 충전소 비교 선택' }).check();
  await page.getByRole('link', { name: '선택한 2곳 비교하기' }).click();
  await expect(page.getByRole('row', { name: /이용 확률 85% 72%/ })).toBeVisible();
  await expect(page.getByText('추천 노선 경부선 · 하행')).toBeVisible();
  expect(requested).toBeGreaterThanOrEqual(2);
});

test('PRD는 자동 노선 미선택과 선택된 노선의 추천 0건을 구분한다', async ({ context, page }) => {
  await context.grantPermissions(['geolocation']);
  await context.setGeolocation({ latitude: 37.23, longitude: 127.10 });
  let selected = false;
  await page.route('**/api/stations?*', route => route.fulfill({ json: { count: 0, limit: 1, truncated: false, statusCollectedAt: null, stations: [] } }));
  await page.route('**/api/recommendations?*', route => route.fulfill({ json: {
    requestedAt: '2026-11-28T10:00:00', reachableKm: 120, candidateCount: 0, unreachableCount: 0, recommendations: [],
    route: selected ? { routeId: 1, routeNo: '1', routeName: '경부선', direction: '하행', autoSelected: true } : null,
  } }));
  await page.goto(`/recommendations?${query}`);
  await page.getByRole('button', { name: '현재 위치', exact: true }).click();
  await expect(page.getByRole('heading', { name: '이동 경로의 고속도로를 찾지 못했어요' })).toBeVisible();
  selected = true;
  await page.getByLabel('남은 주행 가능 거리 (km)').fill('151');
  await expect(page.getByRole('heading', { name: '추천 가능한 충전소가 없어요' })).toBeVisible();
  await expect(page.getByText('추천 노선 경부선 · 하행')).toBeVisible();
});
