import { expect, test } from '@playwright/test';

const demoQuery = 'destination=부산역&destLat=35.1151&destLng=129.0415&remainingRangeKm=150&demo=1';

test('DEV 시연 목적지로 추천 2곳을 선택해 비교한다', async ({ page }) => {
  await page.goto('/recommendations');
  await page.getByRole('button', { name: '시연 목적지 사용' }).click();
  await page.getByLabel('남은 주행 가능 거리 (km)').fill('150');
  await page.getByRole('button', { name: '시연 위치 사용' }).click();
  await expect(page.getByRole('heading', { name: '추천 상위 5곳' })).toBeVisible();
  await expect(page.getByText('추천 노선 경부선 · 하행')).toBeVisible();
  await page.getByRole('checkbox', { name: '안성휴게소(부산) 비교 선택' }).check();
  await page.getByRole('checkbox', { name: '오산IC 인근 충전소 비교 선택' }).check();
  await page.getByRole('link', { name: '선택한 2곳 비교하기' }).click();
  await expect(page.getByRole('heading', { name: '추천 비교' })).toBeVisible();
  await expect(page.getByText('추천 노선 경부선 · 하행')).toBeVisible();
  await expect(page.getByRole('row', { name: /이용 확률 85% 72%/ })).toBeVisible();
  await expect(page.getByRole('row', { name: /예상 대기 정보 없음 정보 없음/ })).toBeVisible();
});

test('빈자리 우선을 기본으로 두고 가까운 순으로 전환할 수 있다', async ({ page }) => {
  await page.goto(`/recommendations?${demoQuery}`);
  await expect(page.locator('.recommend-card').first().getByRole('heading')).toHaveText('안성휴게소(부산)');
  await page.getByRole('button', { name: '가까운 순' }).click();
  await expect(page.locator('.recommend-card').first().getByRole('heading')).toHaveText('기흥휴게소(부산)');
  await page.getByRole('button', { name: '빈자리 우선' }).click();
  await expect(page.locator('.recommend-card').first().getByRole('heading')).toHaveText('안성휴게소(부산)');
});

test('도착시각 선택은 화면에서 가능하지만 추천 결과에는 적용하지 않는다', async ({ page }) => {
  await page.goto(`/recommendations?${demoQuery}`);
  await page.getByRole('link', { name: /도착 예정시각 변경/ }).click();
  await expect(page.getByRole('heading', { name: '도착 예정시각 변경' })).toBeVisible();
  await page.getByRole('button', { name: '45분 후' }).click();
  await expect(page.getByText(/선택한 시간: 45분 후 · 추천 결과에는 미적용/)).toBeVisible();
  await page.getByRole('link', { name: '추천 결과로 돌아가기' }).click();
  await expect(page.getByText(/선택한 45분 후 · 추천 미적용/)).toBeVisible();
});
