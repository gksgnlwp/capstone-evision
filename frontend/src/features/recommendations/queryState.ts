import type { Coordinates } from '../search/contracts';
import type { RecommendationQuery } from './contracts';

export const demoPosition: Coordinates = { lat: 37.23, lng: 127.10 };

export function queryFromParams(params: URLSearchParams, position: Coordinates | null): RecommendationQuery | null {
  const routeId = Number(params.get('routeId'));
  const range = Number(params.get('remainingRangeKm'));
  if (!position || !Number.isSafeInteger(routeId) || routeId < 1 || !Number.isFinite(range) || range <= 0 || range > 1000) return null;
  return { position, routeId, remainingRangeKm: range, direction: params.get('direction') || undefined,
    chargerType: params.get('chargerType') || undefined, limit: 5 };
}
