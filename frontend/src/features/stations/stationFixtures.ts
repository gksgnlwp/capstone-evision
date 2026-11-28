import type { MockHandler } from '../../services/api/mockClient';
import { sampleStations } from '../search/searchFixtures';
import type { ChargerHistory, StationDetail, StationPattern } from './contracts';
import { localKst } from './stationUtils';
function detail(id: number): StationDetail | null {
  const station = sampleStations.find(item => item.stationId === id);
  if (!station) return null;
  const now = localKst(new Date());
  return {
    stationId: station.stationId, statId: station.statId, name: station.name,
    address: '시연용 주소', addressDetail: null, lat: station.lat, lng: station.lng,
    busiId: station.busiId, orgName: '시연 운영기관', operatorName: '시연 운영기관', operatorCall: null,
    useTime: '24시간', parkingFree: null, limited: null, limitDetail: null, infoUpdatedAt: now,
    statusCollectedAt: now, reliability: station.reliability,
    accesses: station.accesses,
    chargers: Array.from({ length: station.fastChargerCount }, (_, index) => {
      const normalizedStatus = index < station.availableCount ? 'AVAILABLE' as const
        : index < station.availableCount + station.chargingCount ? 'CHARGING' as const
          : 'UNKNOWN' as const;
      return { chargerId: station.stationId * 100 + index + 1, chgerId: String(index + 1).padStart(2, '0'),
        chargerType: index % 2 ? '06' : '04', outputKw: index % 2 ? 50 : 100,
        method: '단독', fast: true, normalizedStatus,
        statusCode: normalizedStatus === 'AVAILABLE' ? '2' : normalizedStatus === 'CHARGING' ? '3' : null,
        statusUpdatedAt: normalizedStatus === 'UNKNOWN' ? null : now,
        reliability: normalizedStatus === 'UNKNOWN' ? 'UNKNOWN' as const : 'NORMAL' as const };
    }),
  };
}

function history(chargerId: number, query: Record<string, unknown>): ChargerHistory {
  const now = Date.now();
  const all = Array.from({ length: 25 }, (_, index) => ({
    statusCode: index % 2 ? '3' : '2', normalizedStatus: index % 2 ? 'CHARGING' as const : 'AVAILABLE' as const,
    statusUpdatedAt: localKst(new Date(now - (25 - index) * 45 * 60000)), lastChargeStart: null, lastChargeEnd: null,
    nowChargeStart: null, sourceApi: 'STATUS' as const, collectedAt: localKst(new Date(now - (25 - index) * 45 * 60000 + 60000)),
  }));
  const from = String(query.from);
  const to = String(query.to);
  const cursor = query.cursor ? String(query.cursor) : null;
  const matching = all.filter(item => item.statusUpdatedAt >= from && item.statusUpdatedAt < to && (!cursor || item.statusUpdatedAt > cursor));
  const size = Number(query.size || 100);
  const items = matching.slice(0, size);
  return { chargerId, from, to, size, items, nextCursor: matching.length > size ? items.at(-1)?.statusUpdatedAt ?? null : null };
}

function patterns(station: typeof sampleStations[number]): StationPattern {
  const windowMs = 30 * 60_000;
  const to = Math.floor(Date.now() / windowMs) * windowMs;
  const weeks = 4;
  const slots = Array.from({ length: 336 }, (_, index) => {
    const day = Math.floor(index / 48);
    const slot = index % 48;
    const hour = Math.floor(slot / 2);
    const samples = day === 6 && hour < 3 ? 0 : weeks;
    const rate = (28 + (day * 11 + Math.floor(hour / 3) * 17) % 62) / 100;
    return {
      dayOfWeek: day + 1,
      time: `${String(hour).padStart(2, '0')}:${slot % 2 ? '30' : '00'}`,
      averageOccupancyRate: samples ? rate : null,
      comfortRate: samples ? (rate < 0.5 ? 1 : 0) : null,
      samples,
    };
  });
  const totalWindows = weeks * 336;
  const validWindows = slots.reduce((sum, slot) => sum + slot.samples, 0);
  return {
    stationId: station.stationId, stationName: station.name, weeks,
    from: localKst(new Date(to - totalWindows * windowMs)), to: localKst(new Date(to)),
    comfortThreshold: 0.5, totalWindows, validWindows, excludedWindows: totalWindows - validWindows, slots,
  };
}

export const stationHandlers: Record<string, MockHandler> = {};
for (const station of sampleStations) {
  stationHandlers[`GET stations/${station.stationId}`] = () => detail(station.stationId);
  stationHandlers[`GET stations/${station.stationId}/patterns`] = () => patterns(station);
  for (let index = 0; index < station.fastChargerCount; index++) {
    const chargerId = station.stationId * 100 + index + 1;
    stationHandlers[`GET chargers/${chargerId}/history`] = ({ query = {} }) => history(chargerId, query);
  }
}
