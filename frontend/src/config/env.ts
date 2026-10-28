export interface AppConfig {
  dataMode: 'mock' | 'api';
  apiBaseUrl: string;
  apiTimeoutMs: number;
}

export function parseConfig(env: Record<string, string | undefined>): AppConfig {
  const dataMode = env.VITE_DATA_MODE || 'mock';
  if (dataMode !== 'mock' && dataMode !== 'api') {
    throw new Error('VITE_DATA_MODE는 mock 또는 api여야 합니다.');
  }
  const apiBaseUrl = env.VITE_API_BASE_URL || '/api/';
  if (!apiBaseUrl.startsWith('/') && !/^https?:\/\//.test(apiBaseUrl)) {
    throw new Error('VITE_API_BASE_URL은 /로 시작하는 경로 또는 HTTP URL이어야 합니다.');
  }
  if (apiBaseUrl.startsWith('//') || /[?#]/.test(apiBaseUrl)) {
    throw new Error('VITE_API_BASE_URL에 프로토콜 상대 URL, 쿼리 또는 해시를 사용할 수 없습니다.');
  }
  const apiTimeoutMs = Number(env.VITE_API_TIMEOUT_MS || 10000);
  if (!Number.isFinite(apiTimeoutMs) || apiTimeoutMs <= 0) {
    throw new Error('VITE_API_TIMEOUT_MS는 양수여야 합니다.');
  }
  return { dataMode, apiBaseUrl: apiBaseUrl.replace(/\/*$/, '/'), apiTimeoutMs };
}

export const config = parseConfig(import.meta.env);
