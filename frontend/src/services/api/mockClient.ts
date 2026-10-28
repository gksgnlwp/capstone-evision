import { ApiError, type ApiClient, type RequestOptions } from './types';

export interface MockRequest extends RequestOptions { path: string }
export type MockHandler = (request: MockRequest) => unknown | Promise<unknown>;

/** 기능별 fixture/handler를 2~6번 작업에서 주입합니다. 미등록 요청은 성공으로 가장하지 않습니다. */
export function createMockClient(handlers: Record<string, MockHandler> = {}, delayMs = 180): ApiClient {
  return {
    async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
      const { signal } = options;
      if (signal?.aborted) throw signal.reason || new DOMException('요청 취소', 'AbortError');
      await new Promise<void>((resolve, reject) => {
        const abort = () => {
          clearTimeout(timer);
          reject(signal?.reason || new DOMException('요청 취소', 'AbortError'));
        };
        const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, delayMs);
        signal?.addEventListener('abort', abort, { once: true });
      });
      const handler = handlers[`${options.method || 'GET'} ${path}`];
      if (!handler) throw new ApiError('이 기능의 예시 데이터는 아직 준비되지 않았습니다.', 'not-implemented');
      return await handler({ ...options, path }) as T;
    },
  };
}
