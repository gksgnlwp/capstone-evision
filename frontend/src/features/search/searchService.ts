import { z } from 'zod';
import { apiClient, ApiError, type ApiClient } from '../../services/api';
import { config } from '../../config/env';
import { createMockClient } from '../../services/api/mockClient';
import { searchHandlers } from './searchFixtures';
import { routesResponseSchema, searchResponseSchema, validateSearchQuery, type SearchQuery } from './contracts';
import { deduplicateStations } from './searchUtils';

export function createSearchService(client: ApiClient) {
  async function parse<T>(schema: z.ZodType<T>, request: Promise<unknown>): Promise<T> {
    const result = schema.safeParse(await request);
    if (!result.success) throw new ApiError('충전소 정보의 응답 형식을 확인할 수 없습니다.', 'invalid-response');
    return result.data;
  }
  return {
    async getRoutes(signal?: AbortSignal) {
      return (await parse(routesResponseSchema, client.request('routes', { signal, credentials: 'omit' }))).routes;
    },
    async search(query: SearchQuery, signal?: AbortSignal) {
      validateSearchQuery(query);
      const { bounds, ...filters } = query;
      const result = await parse(searchResponseSchema, client.request('stations', {
        signal, credentials: 'omit', query: { ...filters, ...bounds },
      }));
      const stations = deduplicateStations(result.stations);
      return { ...result, stations, count: stations.length, receivedAt: Date.now() };
    },
  };
}

export const searchService = createSearchService(config.environment === 'DEV' ? createMockClient(searchHandlers) : apiClient);
