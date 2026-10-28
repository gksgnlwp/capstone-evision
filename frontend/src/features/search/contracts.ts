import { z } from 'zod';

const coordinate = z.number().finite();
export const boundsSchema = z.object({
  minLat: coordinate.min(-90).max(90), minLng: coordinate.min(-180).max(180),
  maxLat: coordinate.min(-90).max(90), maxLng: coordinate.min(-180).max(180),
}).refine(b => b.minLat <= b.maxLat && b.minLng <= b.maxLng, '잘못된 지도 영역입니다.');

export const accessSchema = z.object({
  accessType: z.enum(['REST_AREA', 'IC']),
  restAreaId: z.number().int().nullable(), restAreaName: z.string().nullable(), direction: z.string().nullable(),
  icId: z.number().int().nullable(), icName: z.string().nullable(), facilityType: z.string().nullable(),
  routeId: z.number().int().nullable(), routeNo: z.string().nullable(), routeName: z.string().nullable(),
  detourKm: z.number().nonnegative().nullable(),
});

export const stationSchema = z.object({
  stationId: z.number().int().positive(), statId: z.string(), name: z.string(), busiId: z.string().nullable(),
  lat: coordinate.min(-90).max(90), lng: coordinate.min(-180).max(180),
  accesses: z.array(accessSchema), fastChargerCount: z.number().int().nonnegative(),
  availableCount: z.number().int().nonnegative(), chargingCount: z.number().int().nonnegative(),
  unavailableCount: z.number().int().nonnegative(), unknownCount: z.number().int().nonnegative(),
  latestStatusUpdatedAt: z.string().nullable(),
});

export const searchResponseSchema = z.object({
  count: z.number().int().nonnegative(), limit: z.number().int().min(1).max(500),
  truncated: z.boolean(), stations: z.array(stationSchema),
});
export const routesResponseSchema = z.object({ routes: z.array(z.object({
  routeId: z.number().int().positive(), routeNo: z.string(), routeName: z.string(), directions: z.array(z.string()),
  restAreaStations: z.number().int().nonnegative(), icStations: z.number().int().nonnegative(),
})) });

export type StationSummary = z.infer<typeof stationSchema>;
export type AccessPoint = z.infer<typeof accessSchema>;
export type RouteItem = z.infer<typeof routesResponseSchema>['routes'][number];
export type Bounds = z.infer<typeof boundsSchema>;
export type SearchResponse = z.infer<typeof searchResponseSchema>;
export interface Coordinates { lat: number; lng: number }
export interface SearchQuery {
  routeId?: number;
  direction?: string;
  bounds?: Bounds;
  accessType?: 'REST_AREA' | 'IC';
  chargerType?: string;
  limit?: number;
}

export function validateSearchQuery(query: SearchQuery) {
  if (query.routeId === undefined && !query.bounds) throw new Error('노선 또는 지도 영역을 선택해 주세요.');
  if (query.routeId !== undefined && (!Number.isSafeInteger(query.routeId) || query.routeId < 1)) throw new Error('노선을 다시 선택해 주세요.');
  if (query.direction && query.routeId === undefined) throw new Error('방향은 노선과 함께 선택해야 합니다.');
  if (query.bounds) boundsSchema.parse(query.bounds);
  if (query.limit !== undefined && (!Number.isInteger(query.limit) || query.limit < 1 || query.limit > 500)) throw new Error('조회 건수는 1~500이어야 합니다.');
}
