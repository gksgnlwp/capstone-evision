import { expect, test } from '@playwright/test';

test('DEV 운영자 화면은 로그인 시연에서 여섯 화면으로 이동하고 수집 기간을 바꾼다', async ({ page }) => {
  await page.goto('/admin/login');
  await expect(page.getByRole('heading', { name: '운영자 로그인' })).toBeVisible();
  await page.getByRole('button', { name: /시연 화면 보기/ }).click();
  await expect(page.getByRole('heading', { name: '수집 모니터링' })).toBeVisible();
  await expect(page.getByText('30분 구간 데이터 가용률')).toBeVisible();
  await page.getByRole('button', { name: '7일', exact: true }).click();
  await expect(page.getByRole('button', { name: '7일', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByText('2,002 / 7 / 14')).toBeVisible();
  for (const [name, heading] of [
    ['거점별 운영 통계', '거점별 운영 통계'], ['예측 정확도', '예측 정확도'],
    ['기준정보 · 매핑', '기준정보 · 매핑'], ['모델 학습 · 반영', '모델 학습 · 반영'],
  ]) {
    await page.getByRole('navigation', { name: '운영 메뉴' }).getByRole('link', { name }).click();
    await expect(page.getByRole('heading', { name: heading })).toBeVisible();
  }
});

test('운영 통계 필터는 표와 CSV 대상에 같은 조건을 적용한다', async ({ page }) => {
  await page.goto('/admin/statistics');
  await page.getByLabel('거점 유형').selectOption('IC 인근');
  await expect(page.getByRole('cell', { name: '서안성IC 인근' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '안성휴게소(부산)' })).toHaveCount(0);
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'CSV 다운로드' }).click();
  expect((await download).suggestedFilename()).toBe('evision-demo-statistics.csv');
});
