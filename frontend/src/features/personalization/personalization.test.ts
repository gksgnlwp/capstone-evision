import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../services/api';
import { createMockClient } from '../../services/api/mockClient';
import { personalizationHandlers } from './personalizationFixtures';
import { createPersonalizationService } from './personalizationService';
import { favoriteLimit, removeFavorite, setPreferredOperators, toggleFavorite } from './preferences';

const service = createPersonalizationService(createMockClient(personalizationHandlers, 0));
const originalStorage = Object.getOwnPropertyDescriptor(window, 'localStorage');

beforeEach(() => {
  const values = new Map<string, string>();
  Object.defineProperty(window, 'localStorage', { configurable: true, value: {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); },
    clear: () => { values.clear(); },
  } });
  window.dispatchEvent(new StorageEvent('storage', { key: null }));
});

afterEach(() => {
  window.localStorage.clear();
  window.dispatchEvent(new StorageEvent('storage', { key: null }));
  if (originalStorage) Object.defineProperty(window, 'localStorage', originalStorage);
});

describe('선호 기관·즐겨찾기', () => {
  it('운영기관과 즐겨찾기 상태를 백엔드 조회 경로로 요청한다', async () => {
    const operators = await service.operators();
    const summaries = await service.favorites([104, 105]);
    const request = vi.fn().mockResolvedValueOnce({ operators }).mockResolvedValueOnce({ items: summaries });
    const live = createPersonalizationService({ request } as ApiClient);
    await live.operators();
    await live.favorites([104, 105]);
    expect(request.mock.calls[0]).toMatchObject(['operators', { credentials: 'omit' }]);
    expect(request.mock.calls[1]).toMatchObject(['stations/summaries', { credentials: 'omit', query: { ids: '104,105' } }]);
    expect(summaries.map(item => item.stationId)).toEqual([104, 105]);
  });
  it('20개 한도와 중복·잘못된 응답을 검사한다', async () => {
    await expect(service.favorites([])).rejects.toThrow('최대 20곳');
    await expect(service.favorites([104, 104])).rejects.toThrow('서로 다른');
    const invalid = createPersonalizationService({ request: vi.fn().mockResolvedValue({ items: [] }) } as ApiClient);
    await expect(invalid.favorites([104])).rejects.toMatchObject({ kind: 'invalid-response' });
  });
  it('선호 기관과 즐겨찾기를 브라우저에만 저장하고 20개까지만 추가한다', () => {
    setPreferredOperators(['ME', 'ME', 'ST']);
    expect(JSON.parse(localStorage.getItem('evision.preferredOperatorIds') || '[]')).toEqual(['ME', 'ST']);
    for (let id = 1; id <= favoriteLimit; id++) expect(toggleFavorite(id)).toBe('added');
    expect(toggleFavorite(21)).toBe('limit');
    expect(JSON.parse(localStorage.getItem('evision.favoriteStationIds') || '[]')).toHaveLength(20);
    removeFavorite(1);
    expect(toggleFavorite(21)).toBe('added');
  });
});
