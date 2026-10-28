import { expect, test } from '@playwright/test';

// 키 없이 지도 미설정 상태를 검사하며 외부 SDK를 호출하지 않습니다.
test.beforeEach(async ({ page }) => { await page.route('https://dapi.kakao.com/**', route => route.abort()); });

const screenPaths = [
  '/', '/route', '/map', '/settings/operators', '/recommendations/arrival', '/recommendations', '/compare',
  '/stations/preview-station', '/stations/preview-station/prediction', '/stations/preview-station/patterns',
  '/stations/preview-station/chargers/preview-charger/history', '/favorites', '/location',
  '/stations/preview-station/prediction-unavailable', '/admin/login', '/admin/collection',
  '/admin/statistics', '/admin/accuracy', '/admin/mappings', '/admin/models', '/settings',
];

test('20개 화면과 설정 경로가 직접 접근되며 모바일 폭을 넘지 않는다', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  for (const path of screenPaths) {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  }
  expect(errors).toEqual([]);
});

test('하단 메뉴가 화면을 전환하고 현재 메뉴를 표시한다', async ({ page }) => {
  await page.goto('/');
  const nav = page.getByRole('navigation', { name: '주요 메뉴' });
  await nav.getByRole('link', { name: '추천', exact: true }).click();
  await expect(page).toHaveURL(/\/recommendations$/);
  await expect(nav.getByRole('link', { name: '추천', exact: true })).toHaveAttribute('aria-current', 'page');
  await page.reload();
  await expect(page.getByRole('heading', { name: '충전소 추천', exact: true })).toBeVisible();
});

test('대화상자는 포커스를 받고 Escape로 닫힌다', async ({ page }) => {
  await page.goto('/dev/ui');
  await page.getByRole('button', { name: '안내 열기' }).click();
  await expect(page.getByRole('dialog', { name: '공통 안내' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(page.getByRole('button', { name: '안내 열기' })).toBeFocused();
});

test('넓은 화면에서 운영 레이아웃을 표시한다', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/admin/collection');
  await expect(page.getByRole('navigation', { name: '운영 메뉴' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '수집 모니터링' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
