import { afterEach, describe, expect, it, vi } from 'vitest';
import { buildRequestUrl, createHttpClient } from './httpClient';

const client = createHttpClient({ apiBaseUrl: '/api/', apiTimeoutMs: 100 });
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('HTTP 연동 기반', () => {
  it('쿼리를 인코딩하고 누락 값만 생략한다', () => {
    const url = new URL(buildRequestUrl('/api/', 'stations', { query: { name: '부산 방향', enabled: false, page: 0, absent: undefined, ids: ['a', 'b'] } }));
    expect(url.pathname).toBe('/api/stations');
    expect(url.searchParams.get('name')).toBe('부산 방향');
    expect(url.searchParams.get('enabled')).toBe('false');
    expect(url.searchParams.get('page')).toBe('0');
    expect(url.searchParams.has('absent')).toBe(false);
    expect(url.searchParams.getAll('ids')).toEqual(['a', 'b']);
  });
  it('외부 URL과 기본 경로 탈출을 거부한다', () => {
    for (const path of ['https://evil.test', '//evil.test', '../private', 'stations?secret=1']) {
      expect(() => buildRequestUrl('/api/', path)).toThrow();
    }
  });
  it('JSON을 전송하고 결과를 읽는다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true })));
    vi.stubGlobal('fetch', fetchMock);
    expect(await client.request('example', { method: 'POST', body: { value: 1 } })).toEqual({ ok: true });
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ credentials: 'include', body: '{"value":1}', method: 'POST' });
  });
  it('401을 예시 데이터로 대체하지 않고 오류로 전달한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('internal details', { status: 401 })));
    await expect(client.request('private')).rejects.toMatchObject({ kind: 'http', status: 401, message: '운영자 인증이 필요합니다.' });
  });
  it('응답 없음과 형식 오류를 구분한다', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(null, { status: 204 })).mockResolvedValueOnce(new Response('<html>Error</html>'));
    vi.stubGlobal('fetch', fetchMock);
    await expect(client.request('empty')).resolves.toBeUndefined();
    await expect(client.request('broken')).rejects.toMatchObject({ kind: 'invalid-response' });
  });
  it('타임아웃을 네트워크 오류와 구분한다', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn((_url, init: RequestInit) => new Promise((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    })));
    const result = expect(client.request('slow')).rejects.toMatchObject({ kind: 'timeout' });
    await vi.advanceTimersByTimeAsync(101);
    await result;
  });
});
