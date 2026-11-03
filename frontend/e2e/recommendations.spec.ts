import { expect, test } from '@playwright/test';

test('DEV 추천 2곳을 선택해 실제 응답 항목으로 비교한다', async ({ page }) => {
  await page.goto('/recommendations?routeId=1&direction=하행');
  await page.getByLabel('남은 주행 가능 거리 (km)').fill('150');
  await page.getByRole('button', { name: '시연 위치 사용' }).click();
  await expect(page.getByRole('heading', { name: '추천 상위 5곳' })).toBeVisible();
  await page.getByRole('checkbox', { name: '안성휴게소(부산) 비교 선택' }).check();
  await page.getByRole('checkbox', { name: '오산IC 인근 충전소 비교 선택' }).check();
  await page.getByRole('link', { name: '선택한 2곳 비교하기' }).click();
  await expect(page.getByRole('heading', { name: '추천 비교' })).toBeVisible();
  await expect(page.getByRole('row', { name: /이용 확률 85% 72%/ })).toBeVisible();
  await expect(page.getByRole('row', { name: /예상 대기 정보 없음 정보 없음/ })).toBeVisible();
});

test('도착시각 변경 화면은 API 미지원 상태를 정확히 알린다', async ({ page }) => {
  await page.goto('/recommendations?routeId=1&direction=하행&remainingRangeKm=150&demo=1');
  await page.getByRole('link', { name: /도착 예정시각 변경/ }).click();
  await expect(page.getByRole('heading', { name: '도착 예정시각 변경' })).toBeVisible();
  await expect(page.getByText(/추천 API에 도착시각 입력이 추가되면/)).toBeVisible();
  await expect(page.getByRole('button', { name: '45분 후' })).toBeDisabled();
});
