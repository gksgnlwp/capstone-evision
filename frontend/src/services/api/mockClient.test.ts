import { describe, expect, it } from 'vitest';
import { createMockClient } from './mockClient';

describe('mock 연동 기반', () => {
  it('등록한 핸들러만 응답한다', async () => {
    const client = createMockClient({ 'GET /example': () => ({ mode: 'example' }) }, 0);
    await expect(client.request('/example')).resolves.toEqual({ mode: 'example' });
    await expect(client.request('/missing')).rejects.toMatchObject({ kind: 'not-implemented' });
  });
  it('취소된 요청은 실행하지 않는다', async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(createMockClient().request('/example', { signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' });
  });
});
