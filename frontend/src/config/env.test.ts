import { describe, expect, it } from 'vitest';
import { parseConfig } from './env';

describe('환경 설정', () => {
  it('미설정 상태에서 mock 모드를 사용한다', () => {
    expect(parseConfig({})).toEqual({ dataMode: 'mock', apiBaseUrl: '/api/', apiTimeoutMs: 10000 });
  });
  it('지원하지 않는 모드와 잘못된 타임아웃을 거부한다', () => {
    expect(() => parseConfig({ VITE_DATA_MODE: 'production' })).toThrow();
    expect(() => parseConfig({ VITE_API_TIMEOUT_MS: 'no' })).toThrow();
    expect(() => parseConfig({ VITE_API_TIMEOUT_MS: '-1' })).toThrow();
  });
  it('base URL을 정리하고 위험한 경로를 거부한다', () => {
    expect(parseConfig({ VITE_API_BASE_URL: 'https://example.com/api' }).apiBaseUrl).toBe('https://example.com/api/');
    expect(() => parseConfig({ VITE_API_BASE_URL: '//example.com/' })).toThrow();
    expect(() => parseConfig({ VITE_API_BASE_URL: '/api/?key=secret' })).toThrow();
  });
});
