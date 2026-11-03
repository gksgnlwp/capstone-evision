import { config } from '../../config/env';
import { apiClient, ApiError, type ApiClient } from '../../services/api';
import { createMockClient } from '../../services/api/mockClient';
import { stationHandlers } from './stationFixtures';
import { chargerHistorySchema, stationDetailSchema, validateHistoryQuery, validateId, type HistoryQuery } from './contracts';

export function createStationService(client: ApiClient) {
  return {
    async detail(stationId: number, signal?: AbortSignal) {
      validateId(stationId, '충전소');
      const response = await client.request(`stations/${stationId}`, { signal, credentials: 'omit' });
      const parsed = stationDetailSchema.safeParse(response);
      if (!parsed.success) throw new ApiError('충전소 상세 응답 형식을 확인할 수 없습니다.', 'invalid-response');
      return parsed.data;
    },
    async history(query: HistoryQuery, signal?: AbortSignal) {
      validateHistoryQuery(query);
      const { chargerId, ...params } = query;
      const response = await client.request(`chargers/${chargerId}/history`, { signal, credentials: 'omit', query: params });
      const parsed = chargerHistorySchema.safeParse(response);
      if (!parsed.success) throw new ApiError('충전기 이력 응답 형식을 확인할 수 없습니다.', 'invalid-response');
      return parsed.data;
    },
  };
}

export const stationService = createStationService(config.environment === 'DEV' ? createMockClient(stationHandlers) : apiClient);
