import type { Coordinates } from '../search/contracts';
import { validCoordinates, type RecommendationQuery } from './contracts';

export const demoPosition: Coordinates = { lat: 37.23, lng: 127.10 };
export type Destination = Coordinates & { name: string };
export const demoDestination: Destination = { name: '부산역 (시연)', lat: 35.1151, lng: 129.0415 };

export function destinationFromParams(params: URLSearchParams) {
  const name = params.get('destination')?.trim().slice(0, 100);
  const lat = params.get('destLat')?.trim();
  const lng = params.get('destLng')?.trim();
  if (!name || !lat || !lng) return null;
  const destination = { name, lat: Number(lat), lng: Number(lng) };
  return validCoordinates(destination) ? destination : null;
}

export function queryFromParams(params: URLSearchParams, position: Coordinates | null): RecommendationQuery | null {
  const destination = destinationFromParams(params);
  const range = Number(params.get('remainingRangeKm'));
  if (!position || !destination || !Number.isFinite(range) || range <= 0 || range > 1000) return null;
  return { position, destination, remainingRangeKm: range, chargerType: params.get('chargerType') || undefined, limit: 5 };
}
