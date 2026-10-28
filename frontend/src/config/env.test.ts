import { describe, expect, it } from 'vitest';
import { parseConfig } from './env';

describe('환경 설정', () => {
  it('개발 기본값은 DEV이고 운영 빌드 기본값은 PRD다', () => {
    expect(parseConfig({})).toEqual({ environment: 'DEV', apiBaseUrl: '/api/', apiTimeoutMs: 10000, kakaoMapAppKey: '' });
    expect(parseConfig({ MODE: 'production' }).environment).toBe('PRD');
  });
  it('환경 설정은 명시적 DEV 또는 PRD만 허용한다', () => {
    expect(parseConfig({ VITE_APP_ENV: 'PRD', MODE: 'development' }).environment).toBe('PRD');
    expect(() => parseConfig({ VITE_APP_ENV: 'staging' })).toThrow('VITE_APP_ENV');
  });
  it('잘못된 타임아웃을 거부한다', () => {
    expect(() => parseConfig({ VITE_API_TIMEOUT_MS: 'no' })).toThrow();
    expect(() => parseConfig({ VITE_API_TIMEOUT_MS: '-1' })).toThrow();
  });
  it('base URL을 정리하고 위험한 경로를 거부한다', () => {
    expect(parseConfig({ VITE_API_BASE_URL: 'https://example.com/api' }).apiBaseUrl).toBe('https://example.com/api/');
    expect(() => parseConfig({ VITE_API_BASE_URL: '//example.com/' })).toThrow();
    expect(() => parseConfig({ VITE_API_BASE_URL: '/api/?key=secret' })).toThrow();
  });
});
