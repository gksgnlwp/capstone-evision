export type QueryValue = string | number | boolean | null | undefined;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  query?: Record<string, QueryValue | QueryValue[]>;
  body?: unknown;
  signal?: AbortSignal;
  credentials?: RequestCredentials;
}

export interface ApiClient {
  /** 제네릭 타입은 문서 역할만 합니다. 실제 응답 검증은 기능별 어댑터에서 수행합니다. */
  request<T>(path: string, options?: RequestOptions): Promise<T>;
}

export type ApiErrorKind = 'http' | 'network' | 'timeout' | 'invalid-response' | 'not-implemented';

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly kind: ApiErrorKind,
    public readonly status?: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
