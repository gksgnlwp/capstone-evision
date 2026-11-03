import type { MockHandler } from '../../services/api/mockClient';
import { sampleStations } from '../search/searchFixtures';
import type { ChargerHistory, StationDetail } from './contracts';

function localKst(date: Date) { return new Date(date.getTime() + 9 * 3600000).toISOString().slice(0, 19); }
function detail(id: number): StationDetail | null {
  const station = sampleStations.find(item => item.stationId === id);
  if (!station) return null;
  const now = localKst(new Date());
  return {
    stationId: station.stationId, statId: station.statId, name: station.name,
    address: '시연용 주소', addressDetail: null, lat: station.lat, lng: station.lng,
    busiId: station.busiId, orgName: '시연 운영기관', operatorName: '시연 운영기관', operatorCall: null,
    useTime: '24시간', parkingFree: null, limited: null, limitDetail: null, infoUpdatedAt: now,
    accesses: station.accesses,
    chargers: Array.from({ length: station.fastChargerCount }, (_, index) => {
      const normalizedStatus = index < station.availableCount ? 'AVAILABLE' as const
        : index < station.availableCount + station.chargingCount ? 'CHARGING' as const
          : 'UNKNOWN' as const;
      return { chargerId: station.stationId * 100 + index + 1, chgerId: String(index + 1).padStart(2, '0'),
        chargerType: index % 2 ? '06' : '04', outputKw: index % 2 ? 50 : 100,
        method: '단독', fast: true, normalizedStatus,
        statusCode: normalizedStatus === 'AVAILABLE' ? '2' : normalizedStatus === 'CHARGING' ? '3' : null,
        statusUpdatedAt: normalizedStatus === 'UNKNOWN' ? null : now };
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

export const stationHandlers: Record<string, MockHandler> = {};
for (const station of sampleStations) {
  stationHandlers[`GET stations/${station.stationId}`] = () => detail(station.stationId);
  for (let index = 0; index < station.fastChargerCount; index++) {
    const chargerId = station.stationId * 100 + index + 1;
    stationHandlers[`GET chargers/${chargerId}/history`] = ({ query = {} }) => history(chargerId, query);
  }
}
