import { expect, test } from '@playwright/test';
import type { StationPattern } from '../src/features/stations/contracts';
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

test('PRD 혼잡 패턴은 상세에서 이어지고 실제 계약의 집계와 표본 없는 칸을 표시한다', async ({ page }) => {
  await page.route('**/api/stations/104', async route => {
    const json = await stationHandlers['GET stations/104']({ path: 'stations/104' });
    await route.fulfill({ json });
  });
  let attempts = 0;
  await page.route('**/api/stations/104/patterns?*', async route => {
    const url = new URL(route.request().url());
    expect(url.searchParams.get('weeks')).toBe('4');
    if (++attempts === 1) return route.fulfill({ status: 500 });
    const json = await stationHandlers['GET stations/104/patterns']({ path: 'stations/104/patterns' });
    return route.fulfill({ json });
  });
  await page.goto('/stations/104');
  await page.getByRole('link', { name: '혼잡 패턴', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByRole('button', { name: '다시 시도' }).click();
  await expect(page.getByRole('heading', { name: '안성휴게소(부산)' })).toBeVisible();
  await expect(page.getByText(/최근 4주/)).toBeVisible();
  const occupancy = page.getByRole('table', { name: '요일별 시간대 평균 점유율' });
  await expect(occupancy.getByRole('cell', { name: '28% 표본 24개' }).first()).toBeVisible();
  await expect(occupancy.getByRole('cell', { name: '데이터 없음' })).toBeVisible();
  await page.getByRole('button', { name: '쾌적 비율' }).click();
  await expect(page.getByRole('table', { name: '요일별 시간대 쾌적 비율' })).toBeVisible();
  await expect(page.getByText(/사용률이 50% 미만/)).toBeVisible();
  await expect(page.getByText('시연 데이터', { exact: true })).toHaveCount(0);
});

test('PRD 혼잡 패턴은 관측값이 없을 때와 충전소가 없을 때를 구분한다', async ({ page }) => {
  await page.route('**/api/stations/104/patterns?*', async route => {
    const pattern = await stationHandlers['GET stations/104/patterns']({ path: 'stations/104/patterns' }) as StationPattern;
    return route.fulfill({ json: {
      ...pattern, totalWindows: 0, validWindows: 0, excludedWindows: 0,
      slots: pattern.slots.map(slot => ({ ...slot, samples: 0, averageOccupancyRate: null, comfortRate: null })),
    } });
  });
  await page.route('**/api/stations/999/patterns?*', route => route.fulfill({ status: 404 }));
  await page.goto('/stations/104/patterns');
  await expect(page.getByRole('heading', { name: '집계할 혼잡 패턴이 없어요' })).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
  await page.goto('/stations/999/patterns');
  await expect(page.getByRole('heading', { name: '충전소를 찾을 수 없어요' })).toBeVisible();
});
