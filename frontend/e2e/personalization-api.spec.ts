import { expect, test } from '@playwright/test';
import { sampleStations } from '../src/features/search/searchFixtures';

test('PRD 개인화 화면은 운영기관·즐겨찾기 요약 API를 사용한다', async ({ page }) => {
  let operatorRequests = 0;
  let summaryRequests = 0;
  await page.route('**/api/operators', async route => {
    operatorRequests++;
    await route.fulfill({ json: { operators: [{ busiId: 'ME', name: '실제 응답 기관', stationCount: 7 }] } });
  });
  await page.route('**/api/stations/summaries?*', async route => {
    summaryRequests++;
    expect(new URL(route.request().url()).searchParams.get('ids')).toBe('104');
    await route.fulfill({ json: { items: [{ stationId: 104, status: 'ACTIVE', station: sampleStations.find(station => station.stationId === 104) }] } });
  });
  await page.addInitScript(() => localStorage.setItem('evision.favoriteStationIds', '[104]'));
  await page.goto('/settings');
  await expect(page.getByRole('checkbox', { name: /실제 응답 기관/ })).toBeVisible();
  expect(operatorRequests).toBeGreaterThan(0);
  await page.goto('/favorites');
  await expect(page.getByRole('link', { name: '안성휴게소(부산)' })).toBeVisible();
  expect(summaryRequests).toBeGreaterThan(0);
  await expect(page.getByLabel('DEV 환경')).toHaveCount(0);
});

test('운영 종료된 즐겨찾기는 상세 이동 대신 삭제를 안내한다', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('evision.favoriteStationIds', '[104]'));
  await page.route('**/api/stations/summaries?*', route => route.fulfill({ json: {
    items: [{ stationId: 104, status: 'CLOSED', station: sampleStations.find(station => station.stationId === 104) }],
  } }));
  await page.goto('/favorites');
  await expect(page.getByText('운영 종료', { exact: true })).toBeVisible();
  await expect(page.getByText('운영이 종료된 충전소입니다.')).toBeVisible();
  await expect(page.getByRole('link', { name: '안성휴게소(부산)' })).toHaveCount(0);
  await page.getByRole('button', { name: '안성휴게소(부산) 즐겨찾기 해제' }).click();
  await expect(page.getByText('저장한 충전소가 없어요')).toBeVisible();
});
