import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../services/api';
import { createMockClient } from '../../services/api/mockClient';
import { recommendationHandlers } from './recommendationFixtures';
import { createRecommendationService } from './recommendationService';
import { arrivalLabel, basisLabel, externalDirectionsUrl, groupRecommendations, operatorLabel, reasonLabel, sortRecommendations } from './recommendationUtils';

const query = { position: { lat: 37.23, lng: 127.10 }, routeId: 1, direction: '하행', remainingRangeKm: 150 };

describe('추천 API와 비교 데이터', () => {
  it('백엔드의 필수 좌표·노선·주행거리 파라미터를 보낸다', async () => {
    const fixture = await createRecommendationService(createMockClient(recommendationHandlers, 0)).getRecommendations(query);
    const request = vi.fn().mockResolvedValue(fixture);
    await createRecommendationService({ request } as ApiClient).getRecommendations(query);
    expect(request).toHaveBeenCalledWith('recommendations', { signal: undefined, credentials: 'omit', query: {
      lat: 37.23, lng: 127.10, routeId: 1, direction: '하행', remainingRangeKm: 150, chargerType: undefined, limit: 5,
    } });
  });
  it('휴게소와 IC 결과를 서버 순서대로 읽고 확률·근거를 보존한다', async () => {
    const result = await createRecommendationService(createMockClient(recommendationHandlers, 0)).getRecommendations(query);
    expect(result.recommendations).toHaveLength(5);
    expect(result.recommendations[0]).toMatchObject({ stationId: 104, rank: 1, basis: 'AI_FORECAST', availabilityProbability: .85, busiId: 'ME', orgName: '환경부' });
    expect(operatorLabel({ ...result.recommendations[0], orgName: null })).toBe('ME');
    expect(result.recommendations[1]).toMatchObject({ stationId: 103, rank: 2, access: { accessType: 'IC' } });
    expect(basisLabel(result.recommendations[0])).toBe('AI 예측 기반');
    expect(externalDirectionsUrl(result.recommendations[0])).toContain('map.kakao.com/link/to/');
    expect(reasonLabel(result.recommendations[0])).toContain('휴게소 충전소');
    expect(reasonLabel(result.recommendations[1])).toContain('IC 인근');
    expect(reasonLabel(result.recommendations[0])).not.toContain('휴게소 우선');
  });
  it('도달 범위를 넘는 충전소를 제외하고 후보 수를 알린다', async () => {
    const result = await createRecommendationService(createMockClient(recommendationHandlers, 0)).getRecommendations({ ...query, remainingRangeKm: 10 });
    expect(result.unreachableCount).toBeGreaterThan(0);
    expect(result.recommendations.every(item => item.distanceKm <= result.reachableKm)).toBe(true);
  });
  it('서버 추천 순위를 기본으로 두고 가까운 순은 거리로 정렬한다', async () => {
    const { recommendations } = await createRecommendationService(createMockClient(recommendationHandlers, 0)).getRecommendations(query);
    expect(sortRecommendations(recommendations, 'score')).toEqual(recommendations);
    const nearest = sortRecommendations(recommendations, 'distance');
    expect(nearest.map(item => item.distanceKm)).toEqual([...nearest.map(item => item.distanceKm)].sort((a, b) => a - b));
    expect(nearest.map(item => item.rank).sort((a, b) => a - b)).toEqual(recommendations.map(item => item.rank));
  });
  it('같은 휴게소의 운영기관별 결과를 묶되 각 충전소를 유지한다', async () => {
    const { recommendations } = await createRecommendationService(createMockClient(recommendationHandlers, 0)).getRecommendations(query);
    const first = recommendations[0];
    const another = { ...recommendations[1], access: { ...first.access }, name: '같은 휴게소의 다른 운영기관' };
    const groups = groupRecommendations([first, recommendations[2], another]);
    expect(groups).toHaveLength(2);
    expect(groups[0].items.map(item => item.stationId)).toEqual([first.stationId, another.stationId]);
  });
  it('잘못된 입력과 누락된 확률을 성공 결과로 바꾸지 않는다', async () => {
    const service = createRecommendationService(createMockClient(recommendationHandlers, 0));
    await expect(service.getRecommendations({ ...query, remainingRangeKm: 0 })).rejects.toThrow('남은 주행');
    await expect(createRecommendationService({ request: vi.fn().mockResolvedValue({ recommendations: [{ rank: 1 }] }) } as ApiClient).getRecommendations(query)).rejects.toMatchObject({ kind: 'invalid-response' });
  });
  it('오프셋 없는 서버 도착시각을 한국 시각으로 표시한다', () => {
    expect(arrivalLabel('2026-11-03T14:35:00')).toBe('14:35');
    expect(arrivalLabel('invalid')).toBe('정보 없음');
  });
});
