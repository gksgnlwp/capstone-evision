import { expect, test } from '@playwright/test';

test('상세 화면에서 충전기 상태와 이력을 조회한다', async ({ page }) => {
  await page.goto('/stations/104');
  await expect(page.getByRole('heading', { name: '충전소 상세' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '안성휴게소(부산)' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '급속충전기 상태' })).toBeVisible();
  await expect(page.getByText('이용 가능 3 / 6대')).toBeVisible();
  await expect(page.getByRole('meter', { name: '급속충전기 이용 가능 비율' })).toHaveAttribute('aria-valuenow', '3');
  await page.getByRole('link', { name: '상태 이력 보기' }).first().click();
  await expect(page.getByRole('heading', { name: '충전기 상태 이력' })).toBeVisible();
  await expect(page.getByText('상태 API 수집').first()).toBeVisible();
  await page.getByRole('button', { name: '이력 더 보기' }).click();
  await expect(page.getByRole('button', { name: '이력 더 보기' })).toHaveCount(0);
  await page.getByRole('button', { name: '최근 7일' }).click();
  await expect(page.getByRole('button', { name: '최근 7일' })).toHaveAttribute('aria-pressed', 'true');
});

test('DEV 예측·패턴 화면은 시연값을 명시하고 현재 상태로 이동할 수 있다', async ({ page }) => {
  await page.goto('/stations/104/prediction');
  await expect(page.getByRole('heading', { name: '도착 시점 예측' })).toBeVisible();
  await expect(page.getByText(/실제 예측 결과가 아닙니다/)).toBeVisible();
  await expect(page.getByText('이용 가능 확률 78%')).toBeVisible();
  await page.getByRole('link', { name: '현재 충전기 상태 보기' }).click();
  await expect(page.getByRole('heading', { name: '예측 미제공 안내' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '현재 급속 충전기 상태' })).toBeVisible();
  await page.goto('/stations/104/patterns');
  await expect(page.getByRole('heading', { name: '시간대별 혼잡 패턴' })).toBeVisible();
  await expect(page.getByText(/실제 관측값이 아닙니다/)).toBeVisible();
  await expect(page.getByRole('table', { name: '요일별 시간대 평균 점유율 시연값' })).toBeVisible();
});
