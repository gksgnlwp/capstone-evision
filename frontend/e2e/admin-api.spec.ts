import { expect, test } from '@playwright/test';

test('PRD 운영자 화면은 인증 연동 전 로그인 화면으로 안내한다', async ({ page }) => {
  await page.goto('/admin/collection');
  await expect(page).toHaveURL(/\/admin\/login$/);
  await expect(page.getByRole('button', { name: '로그인' })).toBeDisabled();
  await expect(page.getByText(/운영자 인증 API와 접근 통제/)).toBeVisible();
});
