import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('https://dapi.kakao.com/**', route => route.abort());
});

test('홈에서 목적지를 입력하면 목적지 기반 추천 화면으로 이동한다', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByText('기능별 화면')).toHaveCount(0);
  await expect(page.getByRole('link', { name: /노선·방향 직접 선택하기/ })).toHaveCount(0);
  await page.getByLabel('목적지', { exact: true }).fill('부산');
  await page.getByRole('button', { name: '충전소 추천 받기' }).click();
  await expect(page).toHaveURL(/\/recommendations\?destination=/);
  await expect(page.getByRole('heading', { name: '충전소 추천', level: 1 })).toBeVisible();
  await expect(page.getByLabel('목적지', { exact: true })).toHaveValue('부산');
  await page.goto('/');
  await expect(page.getByRole('button', { name: '부산', exact: true })).toBeVisible();
});

test('예전 노선 선택 주소는 추천 화면으로 보낸다', async ({ page }) => {
  await page.goto('/route?routeId=1&destination=부산');
  await expect(page).toHaveURL(/\/recommendations\?destination=/);
  await expect(page.getByLabel('목적지', { exact: true })).toHaveValue('부산');
  await page.goto('/route');
  await expect(page).toHaveURL(/\/recommendations$/);
});

test('권한 거부 후 목적지·지도 대안과 재요청을 제공한다', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'geolocation', { value: {
      getCurrentPosition: (_success: unknown, error: (e: unknown) => void) => error({ code: 1 }),
    } });
  });
  await page.goto('/location');
  await page.getByRole('button', { name: '현재 위치 확인', exact: true }).click();
  await expect(page.getByRole('heading', { name: '현재 위치를 확인할 수 없어요' })).toBeVisible();
  await expect(page.getByRole('button', { name: '위치 권한 다시 요청' })).toBeVisible();
  await page.getByRole('link', { name: '지도에서 직접 찾기' }).click();
  await expect(page).toHaveURL(/\/map$/);
  await expect(page.getByRole('heading', { name: /충전소 목록/ })).toBeVisible();
});

test('지도에서 위치 요청이 시간 초과되면 지도와 목록을 유지하고 오류를 알린다', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'geolocation', { value: {
      getCurrentPosition: (_success: unknown, error: (e: unknown) => void) => error({ code: 3 }),
    } });
  });
  await page.goto('/map');
  await page.getByRole('button', { name: '현재 위치 확인' }).click();
  await expect(page).toHaveURL(/\/map$/);
  await expect(page.getByRole('alert')).toContainText('브라우저가 위치를 제때 제공하지 않았습니다');
  await expect(page.getByRole('heading', { name: /충전소 목록/ })).toBeVisible();
});

test('위치 허용 시 지도 중심을 이동하고 위치를 영속 저장하지 않는다', async ({ context, page }) => {
  await context.grantPermissions(['geolocation']);
  await context.setGeolocation({ latitude: 37.235, longitude: 127.104 });
  await page.goto('/location');
  await page.getByRole('button', { name: '현재 위치 확인', exact: true }).click();
  await expect(page).toHaveURL(/\/map\?locate=1$/);
  await expect(page.getByText(/직선 0.0km/).first()).toBeVisible();
  expect(await page.evaluate(() => Object.keys(localStorage).length)).toBe(0);
});

test('키가 없으면 카카오맵 준비 안내와 테스트 목록을 표시한다', async ({ page }) => {
  await page.goto('/map');
  await expect(page.getByRole('heading', { name: '카카오맵을 준비하고 있어요' })).toBeVisible();
  await expect(page.getByRole('heading', { name: /충전소 목록/ })).toBeVisible();
  await expect(page.getByLabel('DEV 환경', { exact: true })).toBeVisible();
  await expect(page.getByText('UI 미리보기 · 충전소 정보와 상태는 테스트 데이터입니다.')).toHaveCount(0);
  await expect(page.getByRole('link', { name: '기흥휴게소(부산) 상세 보기', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '이 영역 검색', exact: true })).toHaveCount(0);
  await expect(page.locator('.leaflet-container')).toHaveCount(0);
});
