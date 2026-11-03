import { expect, test } from '@playwright/test';

test('상세에서 즐겨찾기를 저장하고 목록에서 상태 조회·해제한다', async ({ page }) => {
  await page.goto('/stations/104');
  await page.getByRole('button', { name: '즐겨찾기 추가' }).click();
  await expect(page.getByText('즐겨찾기에 추가했어요.')).toBeVisible();
  await page.getByRole('link', { name: '즐겨찾기', exact: true }).click();
  await expect(page.getByText('1 / 20개 · 이 브라우저에 저장')).toBeVisible();
  await expect(page.getByRole('link', { name: '안성휴게소(부산)' })).toBeVisible();
  await page.reload();
  await expect(page.getByText('1 / 20개 · 이 브라우저에 저장')).toBeVisible();
  await page.getByRole('button', { name: '안성휴게소(부산) 즐겨찾기 해제' }).click();
  await expect(page.getByText('저장한 충전소가 없어요')).toBeVisible();
});

test('선호 운영기관을 저장하면 경로 목록에서 해당 기관만 강조한다', async ({ page }) => {
  await page.goto('/settings');
  await page.getByRole('checkbox', { name: /환경부/ }).check();
  await page.getByRole('button', { name: /저장/ }).click();
  await page.goto('/route?routeId=1');
  await expect(page.locator('.station-card--preferred')).not.toHaveCount(0);
  await expect(page.getByText('선호 운영기관').first()).toBeVisible();
  await expect(page.locator('.station-card:not(.station-card--preferred)')).not.toHaveCount(0);
  await page.goto('/settings');
  await expect(page.getByRole('checkbox', { name: /환경부/ })).toBeChecked();
});

test('브라우저 저장소가 막히면 현재 접속 동안만 적용됨을 알린다', async ({ page }) => {
  await page.addInitScript(() => {
    Storage.prototype.setItem = () => { throw new Error('storage blocked'); };
  });
  await page.goto('/settings');
  await page.getByRole('checkbox', { name: /환경부/ }).check();
  await page.getByRole('button', { name: /저장/ }).click();
  await expect(page.getByText('선호 운영기관을 현재 접속 동안 적용했습니다.')).toBeVisible();
  await page.getByRole('link', { name: '홈', exact: true }).click();
  await page.getByRole('link', { name: /노선·방향 직접 선택하기/ }).click();
  await page.getByLabel('고속도로 노선').selectOption('1');
  await expect(page.locator('.station-card--preferred')).not.toHaveCount(0);
  await page.getByRole('link', { name: '안성휴게소(부산) 상세 보기' }).click();
  await page.getByRole('button', { name: '즐겨찾기 추가' }).click();
  await expect(page.getByText(/즐겨찾기는 현재 접속 동안만 유지됩니다/)).toBeVisible();
  await page.getByRole('link', { name: '즐겨찾기', exact: true }).click();
  await expect(page.getByText('1 / 20개 · 현재 접속 동안만 유지')).toBeVisible();
});

test('즐겨찾기 20개 한도에 도달하면 정리 화면으로 안내한다', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('evision.favoriteStationIds', JSON.stringify(Array.from({ length: 20 }, (_, index) => index + 1))));
  await page.goto('/stations/104');
  await page.getByRole('button', { name: '즐겨찾기 추가' }).click();
  await expect(page.getByText(/즐겨찾기는 최대 20개까지 저장할 수 있어요/)).toBeVisible();
  await expect(page.getByRole('link', { name: '즐겨찾기 정리하기' })).toHaveAttribute('href', '/favorites');
});
