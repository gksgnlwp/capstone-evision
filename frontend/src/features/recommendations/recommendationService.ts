import { ApiError, apiClient, type ApiClient } from '../../services/api';
import { createMockClient } from '../../services/api/mockClient';
import { config } from '../../config/env';
import { recommendationHandlers } from './recommendationFixtures';
import { recommendationSchema, validateRecommendationQuery, type RecommendationQuery } from './contracts';

export function createRecommendationService(client: ApiClient) {
  return {
    async getRecommendations(query: RecommendationQuery, signal?: AbortSignal) {
      validateRecommendationQuery(query);
      const response = await client.request('recommendations', {
        signal, credentials: 'omit',
        query: { lat: query.position.lat, lng: query.position.lng, routeId: query.routeId,
          direction: query.direction, remainingRangeKm: query.remainingRangeKm,
          chargerType: query.chargerType, limit: query.limit ?? 5 },
      });
      const parsed = recommendationSchema.safeParse(response);
      if (!parsed.success) throw new ApiError('추천 결과의 응답 형식을 확인할 수 없습니다.', 'invalid-response');
      return parsed.data;
    },
  };
}

export const recommendationService = createRecommendationService(config.environment === 'DEV'
  ? createMockClient(recommendationHandlers) : apiClient);
