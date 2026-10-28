import type { AppConfig } from '../../config/env';
import { ApiError, type ApiClient, type RequestOptions } from './types';

export function buildRequestUrl(base: string, path: string, options?: RequestOptions): string {
  // 호출자는 베이스 아래의 경로만 전달합니다. 외부 URL로 인증 쿠키를 잘못 전송하지 않습니다.
  if (/^[a-z][a-z0-9+.-]*:/i.test(path) || path.startsWith('//') || /[?#]/.test(path)) {
    throw new Error('API 경로는 쿼리를 제외한 상대 경로여야 합니다.');
  }
  const origin = typeof window === 'undefined' ? 'http://localhost' : window.location.origin;
  const baseUrl = new URL(base, origin);
  const url = new URL(path.replace(/^\/+/, ''), baseUrl);
  if (url.origin !== baseUrl.origin || !url.pathname.startsWith(baseUrl.pathname)) {
    throw new Error('API 기본 경로를 벗어날 수 없습니다.');
  }
  for (const [key, value] of Object.entries(options?.query || {})) {
    for (const item of Array.isArray(value) ? value : [value]) {
      if (item !== undefined && item !== null) url.searchParams.append(key, String(item));
    }
  }
  return url.toString();
}

export function createHttpClient(settings: Pick<AppConfig, 'apiBaseUrl' | 'apiTimeoutMs'>): ApiClient {
  return {
    async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
      const url = buildRequestUrl(settings.apiBaseUrl, path, options);
      const controller = new AbortController();
      let timedOut = false;
      const forwardAbort = () => controller.abort(options.signal?.reason);
      if (options.signal?.aborted) forwardAbort();
      else options.signal?.addEventListener('abort', forwardAbort, { once: true });
      const timer = setTimeout(() => {
        timedOut = true;
        controller.abort();
      }, settings.apiTimeoutMs);
      try {
        const response = await fetch(url, {
          method: options.method || 'GET',
          headers: { Accept: 'application/json', ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }) },
          body: options.body === undefined ? undefined : JSON.stringify(options.body),
          credentials: 'include',
          signal: controller.signal,
        });
        if (!response.ok) {
          // 서버의 내부 메시지/스택은 화면에 그대로 노출하지 않습니다.
          const message = response.status === 401 ? '운영자 인증이 필요합니다.'
            : response.status === 403 ? '이 작업을 수행할 권한이 없습니다.'
              : response.status === 404 ? '요청한 정보를 찾을 수 없습니다.'
                : '서버에서 요청을 처리하지 못했습니다.';
          throw new ApiError(message, 'http', response.status);
        }
        if (response.status === 204) return undefined as T;
        const body = await response.text();
        if (!body.trim()) throw new ApiError('서버에서 빈 응답을 받았습니다.', 'invalid-response');
        try { return JSON.parse(body) as T; }
        catch { throw new ApiError('서버 응답 형식을 확인할 수 없습니다.', 'invalid-response'); }
      } catch (error) {
        if (options.signal?.aborted) throw options.signal.reason || new DOMException('요청 취소', 'AbortError');
        if (timedOut) throw new ApiError('응답 시간이 초과되었습니다. 다시 시도해 주세요.', 'timeout');
        if (error instanceof ApiError) throw error;
        throw new ApiError('서버에 연결할 수 없습니다. 네트워크를 확인해 주세요.', 'network');
      } finally {
        clearTimeout(timer);
        options.signal?.removeEventListener('abort', forwardAbort);
      }
    },
  };
}
