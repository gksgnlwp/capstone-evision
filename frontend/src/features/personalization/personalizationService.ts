import { ApiError, apiClient, type ApiClient } from '../../services/api';
import { createMockClient } from '../../services/api/mockClient';
import { config } from '../../config/env';
import { favoriteSummarySchema, operatorListSchema } from './contracts';
import { personalizationHandlers } from './personalizationFixtures';
import { favoriteLimit } from './preferences';

export function createPersonalizationService(client: ApiClient) {
  return {
    async operators(signal?: AbortSignal) {
      const parsed = operatorListSchema.safeParse(await client.request('operators', { signal, credentials: 'omit' }));
      if (!parsed.success) throw new ApiError('운영기관 목록의 응답 형식을 확인할 수 없습니다.', 'invalid-response');
      return parsed.data.operators;
    },
    async favorites(ids: number[], signal?: AbortSignal) {
      if (!ids.length || ids.length > favoriteLimit || ids.some(id => !Number.isSafeInteger(id) || id < 1) || new Set(ids).size !== ids.length) {
        throw new Error('즐겨찾기는 서로 다른 충전소 최대 20곳까지 조회할 수 있습니다.');
      }
      const parsed = favoriteSummarySchema.safeParse(await client.request('stations/summaries', {
        signal, credentials: 'omit', query: { ids: ids.join(',') },
      }));
      if (!parsed.success) throw new ApiError('즐겨찾기 상태의 응답 형식을 확인할 수 없습니다.', 'invalid-response');
      const byId = new Map(parsed.data.items.map(item => [item.stationId, item]));
      if (byId.size !== ids.length || ids.some(id => !byId.has(id))) throw new ApiError('즐겨찾기 상태가 일부 누락되었습니다.', 'invalid-response');
      return ids.map(id => byId.get(id)!);
    },
  };
}

export const personalizationService = createPersonalizationService(config.environment === 'DEV'
  ? createMockClient(personalizationHandlers) : apiClient);
