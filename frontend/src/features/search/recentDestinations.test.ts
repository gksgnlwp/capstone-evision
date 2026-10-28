import { beforeEach, describe, expect, it, vi } from 'vitest';
import { readRecentDestinations, saveRecentDestination } from './recentDestinations';

beforeEach(() => {
  vi.restoreAllMocks();
  // Node의 실험적 localStorage 대신 브라우저 Storage 계약을 가진 테스트 저장소를 씁니다.
  const values = new Map<string, string>();
  const storage: Storage = {
    get length() { return values.size; },
    clear: () => values.clear(),
    getItem: key => values.get(key) ?? null,
    key: index => [...values.keys()][index] ?? null,
    removeItem: key => { values.delete(key); },
    setItem: (key, value) => { values.set(key, value); },
  };
  Object.defineProperty(window, 'localStorage', { configurable: true, value: storage });
});
describe('최근 목적지', () => {
  it('중복을 제거하고 최신 5개까지만 저장한다', () => {
    for (const destination of ['부산', '대구', '강릉', '광주', '대전', '울산', '부산']) saveRecentDestination(destination);
    expect(readRecentDestinations()).toEqual(['부산', '울산', '대전', '광주', '강릉']);
  });
  it('손상된 저장소와 저장 금지 환경에서 실패하지 않는다', () => {
    window.localStorage.setItem('evision.recentDestinations', '{broken');
    expect(readRecentDestinations()).toEqual([]);
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => { throw new Error('storage blocked'); });
    expect(() => saveRecentDestination('부산')).not.toThrow();
  });
});
