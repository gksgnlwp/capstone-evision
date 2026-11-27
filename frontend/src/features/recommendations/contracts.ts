import { z } from 'zod';
import type { Coordinates } from '../search/contracts';

const coordinate = z.number().finite();
const probability = z.number().min(0).max(1);

export const recommendationSchema = z.object({
  requestedAt: z.string(), reachableKm: z.number().nonnegative(),
  route: z.object({
    routeId: z.number().int().positive(), routeNo: z.string().nullable(),
    routeName: z.string().nullable(), direction: z.string().nullable(), autoSelected: z.boolean(),
  }).nullable(),
  candidateCount: z.number().int().nonnegative(), unreachableCount: z.number().int().nonnegative(),
  recommendations: z.array(z.object({
    rank: z.number().int().positive(), stationId: z.number().int().positive(), statId: z.string(), name: z.string(),
    busiId: z.string().min(1), orgName: z.string().nullable(),
    latitude: coordinate.min(-90).max(90), longitude: coordinate.min(-180).max(180),
    access: z.object({
      accessType: z.enum(['REST_AREA', 'IC']), restAreaId: z.number().int().nullable(),
      restAreaName: z.string().nullable(), direction: z.string().nullable(),
      icId: z.number().int().nullable(), icName: z.string().nullable(),
      routeId: z.number().int().nullable(), routeName: z.string().nullable(), detourKm: z.number().nonnegative().nullable(),
    }),
    distanceKm: z.number().nonnegative(), detourKm: z.number().nonnegative(), etaMinutes: z.number().int().nonnegative(), eta: z.string(),
    availabilityProbability: probability, realtimeProbability: probability.nullable(), baselineProbability: probability,
    statisticalProbability: probability,
    basis: z.enum(['AI_FORECAST', 'SAME_DAY_SLOT', 'SAME_DAY_TYPE_SLOT', 'SAME_SLOT', 'STATION_ALL', 'PRIOR']),
    basisSampleCount: z.number().int().nonnegative(), modelVersion: z.string().nullable(),
    fastChargerCount: z.number().int().nonnegative(), availableCount: z.number().int().nonnegative(),
    chargingCount: z.number().int().nonnegative(), maxOutputKw: z.number().int().positive().nullable(),
    score: z.number().finite(), scoreBreakdown: z.object({
      availability: z.number().finite(), detour: z.number().finite(), power: z.number().finite(),
      capacity: z.number().finite(), progress: z.number().finite(),
    }),
  })),
});

export type RecommendationResponse = z.infer<typeof recommendationSchema>;
export type RecommendationItem = RecommendationResponse['recommendations'][number];
export interface RecommendationQuery {
  position: Coordinates;
  destination: Coordinates;
  remainingRangeKm: number;
  chargerType?: string;
  limit?: number;
}

export function validCoordinates({ lat, lng }: Coordinates) {
  return Number.isFinite(lat) && lat >= -90 && lat <= 90 && Number.isFinite(lng) && lng >= -180 && lng <= 180;
}

export function validateRecommendationQuery(query: RecommendationQuery) {
  const { position, destination, remainingRangeKm, limit } = query;
  if (!validCoordinates(position)) throw new Error('현재 위치를 다시 확인해 주세요.');
  if (!validCoordinates(destination)) throw new Error('목적지를 다시 선택해 주세요.');
  if (!Number.isFinite(remainingRangeKm) || remainingRangeKm <= 0 || remainingRangeKm > 1000) throw new Error('남은 주행 가능 거리는 0 초과 1000km 이하로 입력해 주세요.');
  if (limit !== undefined && (!Number.isInteger(limit) || limit < 1 || limit > 20)) throw new Error('추천 건수는 1~20곳이어야 합니다.');
}
