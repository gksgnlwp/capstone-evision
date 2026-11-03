import type { MockHandler } from '../../services/api/mockClient';
import { sampleStations } from '../search/searchFixtures';
import { distanceKm } from '../search/searchUtils';
import type { RecommendationItem } from './contracts';

// 시연용 값입니다. 실제 추천·확률·주행거리·도착시각이 아닙니다.
const demo = [
  { stationId: 104, probability: .85, detour: 0, score: .89, basis: 'AI_FORECAST' as const },
  { stationId: 103, probability: .72, detour: 3.2, score: .78, basis: 'SAME_DAY_SLOT' as const },
  { stationId: 102, probability: .66, detour: 0, score: .73, basis: 'SAME_DAY_SLOT' as const },
  { stationId: 105, probability: .58, detour: 2.4, score: .64, basis: 'PRIOR' as const },
  { stationId: 106, probability: .48, detour: 0, score: .52, basis: 'SAME_SLOT' as const },
];

export const recommendationHandlers: Record<string, MockHandler> = {
  'GET recommendations': ({ query = {} }) => {
    const routeId = Number(query.routeId);
    const position = { lat: Number(query.lat), lng: Number(query.lng) };
    const range = Number(query.remainingRangeKm);
    const reachableKm = Math.round(range * .8 * 10) / 10;
    const candidates = demo.flatMap(row => {
      const station = sampleStations.find(s => s.stationId === row.stationId);
      if (!station) return [];
      const access = station.accesses.find(a => a.routeId === routeId &&
        (!query.direction || a.accessType === 'IC' || a.direction === query.direction || a.direction === '양방향'));
      if (!access || (query.chargerType && !station.chargerTypes.includes(String(query.chargerType)))) return [];
      const distance = Math.round(distanceKm(position, station) * 1.3 * 10) / 10;
      return [{ station, access, row, distance }];
    });
    const reachable = candidates.filter(c => c.distance <= reachableKm);
    const now = new Date();
    const recommendations: RecommendationItem[] = reachable.slice(0, Math.min(Number(query.limit ?? 5), 20)).map(({ station, access, row, distance }, index) => {
      const etaMinutes = Math.max(1, Math.round(distance / 80 * 60));
      return {
        rank: index + 1, stationId: station.stationId, statId: station.statId, name: station.name,
        latitude: station.lat, longitude: station.lng,
        access: { accessType: access.accessType, restAreaId: access.restAreaId, restAreaName: access.restAreaName,
          direction: access.direction, icId: access.icId, icName: access.icName, routeId: access.routeId,
          routeName: access.routeName, detourKm: access.detourKm },
        distanceKm: distance, detourKm: row.detour, etaMinutes, eta: new Date(now.getTime() + etaMinutes * 60000).toISOString(),
        availabilityProbability: row.probability, realtimeProbability: station.unknownCount === station.fastChargerCount ? null : station.availableCount > 0 ? 1 : 0,
        baselineProbability: row.probability, statisticalProbability: row.probability,
        basis: row.basis, basisSampleCount: row.basis === 'PRIOR' ? 0 : 12, modelVersion: row.basis === 'AI_FORECAST' ? 'demo-v1' : null,
        fastChargerCount: station.fastChargerCount, availableCount: station.availableCount,
        chargingCount: station.chargingCount, maxOutputKw: 100, score: row.score,
        scoreBreakdown: { availability: row.probability, detour: row.detour, power: 1, capacity: station.fastChargerCount / 10, progress: 0 },
      };
    });
    return { requestedAt: now.toISOString(), reachableKm, candidateCount: candidates.length,
      unreachableCount: candidates.length - reachable.length, recommendations };
  },
};
