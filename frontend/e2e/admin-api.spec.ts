import { expect, test } from '@playwright/test';

test('PRD 운영자 화면은 인증 연동 전 로그인 화면으로 안내한다', async ({ page }) => {
  await page.goto('/admin/collection');
  await expect(page).toHaveURL(/\/admin\/login$/);
  await page.getByPlaceholder('운영자 계정').fill('admin');
  await page.getByPlaceholder('비밀번호').fill('example');
  await page.getByRole('button', { name: '로그인' }).click();
  await expect(page.getByRole('alert')).toHaveText('운영자 로그인을 현재 사용할 수 없습니다.');
  await expect(page).toHaveURL(/\/admin\/login$/);
});
