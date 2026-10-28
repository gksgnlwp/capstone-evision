import { describe, expect, it, vi } from 'vitest';
import { createSearchService } from './searchService';
import { createMockClient } from '../../services/api/mockClient';
import { searchHandlers, sampleRoutes, sampleStations } from './searchFixtures';
import { deduplicateStations, distanceKm, parseKst, statusTone } from './searchUtils';
import type { ApiClient } from '../../services/api';

const service = createSearchService(createMockClient(searchHandlers, 0));

describe('노선·지도 검색과 백엔드 계약', () => {
  it('원천 방향을 보존한 노선 목록을 읽는다', async () => {
    expect((await service.getRoutes())[0].directions).toContain('하행');
  });
  it('하행 필터에서도 같은 노선 IC와 양방향 휴게소를 유지한다', async () => {
    const { stations } = await service.search({ routeId: 1, direction: '하행' });
    expect(stations.map(s => s.stationId)).toContain(103);
    expect(stations.map(s => s.stationId)).toContain(108);
    expect(stations.map(s => s.stationId)).not.toContain(107);
    expect(stations.map(s => s.stationId)).not.toContain(201);
  });
  it('접근 유형과 충전기 코드를 적용한다', async () => {
    const result = await service.search({ routeId: 1, direction: '하행', accessType: 'IC', chargerType: '06' });
    expect(result.stations.map(s => s.stationId)).toEqual([105, 108]);
    expect(result.stations.every(s => s.accesses.every(a => a.accessType === 'IC'))).toBe(true);
  });
  it('지도 영역의 네 좌표로 검색하고 결과 없음도 정상 응답한다', async () => {
    expect((await service.search({ bounds: { minLat: 37.23, minLng: 127.10, maxLat: 37.24, maxLng: 127.11 } })).stations.map(s => s.stationId)).toEqual([102]);
    expect((await service.search({ bounds: { minLat: 33, minLng: 126, maxLat: 34, maxLng: 127 } })).count).toBe(0);
  });
  it('조회 상한을 지키고 잘린 결과를 알린다', async () => {
    const result = await service.search({ routeId: 1, limit: 1 });
    expect(result.count).toBe(1);
    expect(result.truncated).toBe(true);
    await expect(service.search({ routeId: 1, limit: 501 })).rejects.toThrow();
    await expect(service.search({ direction: '하행' })).rejects.toThrow();
  });
  it('실제 DTO 키와 query 이름을 사용하며 공개 요청에는 인증 쿠키를 붙이지 않는다', async () => {
    const request = vi.fn().mockResolvedValueOnce({ routes: sampleRoutes }).mockResolvedValueOnce({ stations: [sampleStations[0]], count: 1, limit: 100, truncated: false });
    const live = createSearchService({ request } as ApiClient);
    await live.getRoutes();
    await live.search({ routeId: 1, direction: '상행', chargerType: '04' });
    expect(request.mock.calls[0]).toEqual(['routes', { signal: undefined, credentials: 'omit' }]);
    expect(request.mock.calls[1][0]).toBe('stations');
    expect(request.mock.calls[1][1]).toMatchObject({ credentials: 'omit', query: { routeId: 1, direction: '상행', chargerType: '04' } });
  });
  it('누락된 응답 필드나 잘못된 상태 대수를 임의의 0으로 바꾸지 않는다', async () => {
    const request = vi.fn().mockResolvedValue({ count: 1, limit: 100, truncated: false, stations: [{ stationId: 1, availableCount: null }] });
    await expect(createSearchService({ request } as ApiClient).search({ routeId: 1 })).rejects.toMatchObject({ kind: 'invalid-response' });
  });
  it('여러 접근지점에 연결된 동일 충전소를 합치며 상태 대수는 더하지 않는다', () => {
    const s = sampleStations[0];
    const result = deduplicateStations([s, { ...s, accesses: [sampleStations[2].accesses[0]] }]);
    expect(result).toHaveLength(1);
    expect(result[0].accesses).toHaveLength(2);
    expect(result[0].availableCount).toBe(s.availableCount);
  });
  it('미확인 상태와 이용 가능 0대를 구분한다', () => {
    expect(statusTone(sampleStations[7])).toBe('neutral');
    expect(statusTone(sampleStations[5])).toBe('danger');
  });
  it('백엔드의 오프셋 없는 시각을 KST로 해석하고 거리를 직선으로 계산한다', () => {
    expect(parseKst('2026-10-28T14:05:00')?.toISOString()).toBe('2026-10-28T05:05:00.000Z');
    expect(parseKst('invalid')).toBeNull();
    expect(distanceKm({ lat: 37, lng: 127 }, { lat: 37, lng: 127 })).toBe(0);
  });
});
