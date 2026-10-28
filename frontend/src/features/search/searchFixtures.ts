import type { MockHandler } from '../../services/api/mockClient';
import type { AccessPoint, RouteItem, StationSummary } from './contracts';

// UI 시연용 고정 샘플입니다. 운영 서버에서 수집한 충전 상태가 아닙니다.
export const sampleRoutes: RouteItem[] = [
  { routeId: 1, routeNo: '1', routeName: '경부선', directions: ['상행', '하행', '양방향'], restAreaStations: 6, icStations: 3 },
  { routeId: 2, routeNo: '50', routeName: '영동선', directions: ['상행', '하행'], restAreaStations: 2, icStations: 0 },
];
function rest(name: string, direction = '하행', routeId = 1): AccessPoint {
  return { accessType: 'REST_AREA', restAreaId: name.length * 100 + routeId, restAreaName: name, direction,
    icId: null, icName: null, facilityType: null, routeId, routeNo: routeId === 1 ? '1' : '50', routeName: routeId === 1 ? '경부선' : '영동선', detourKm: null };
}
function ic(id: number, name: string, detourKm: number): AccessPoint {
  return { accessType: 'IC', restAreaId: null, restAreaName: null, direction: null, icId: id, icName: name,
    facilityType: 'IC', routeId: 1, routeNo: '1', routeName: '경부선', detourKm };
}
type SampleStation = StationSummary & { chargerTypes: string[] };
function station(stationId: number, name: string, lat: number, lng: number, accesses: AccessPoint[], available: number, total: number, busiId = 'ME', unknown = 0): SampleStation {
  return { stationId, statId: `SAMPLE${stationId}`, name, busiId, lat, lng, accesses,
    fastChargerCount: total, availableCount: available, chargingCount: total - available - unknown,
    unavailableCount: 0, unknownCount: unknown, latestStatusUpdatedAt: unknown === total ? null : '2026-10-28T14:05:00',
    chargerTypes: stationId % 3 === 0 ? ['04', '06'] : ['04', '01', '03', '05'] };
}
export const sampleStations: SampleStation[] = [
  station(101, '서울만남(부산)휴게소', 37.459, 127.040, [rest('서울만남', '하행')], 2, 4),
  station(102, '기흥휴게소(부산)', 37.235, 127.104, [rest('기흥', '하행')], 3, 4, 'ST'),
  station(103, '오산IC 인근 충전소', 37.148, 127.079, [ic(301, '오산IC', 3.2)], 1, 4, 'CV'),
  station(104, '안성휴게소(부산)', 37.013, 127.145, [rest('안성', '하행')], 3, 6),
  station(105, '안성IC 인근 충전소', 37.020, 127.157, [ic(302, '안성IC', 2.4), ic(303, '남안성IC', 3.8)], 2, 2, 'CV'),
  station(106, '망향휴게소(부산)', 36.898, 127.179, [rest('망향', '하행')], 0, 4),
  station(107, '안성휴게소(서울)', 37.040, 127.147, [rest('안성', '상행')], 1, 4),
  station(108, '양방향 충전소 예시', 37.206, 127.121, [rest('통합휴게소', '양방향'), ic(304, '기흥IC', 1.4)], 0, 3, 'ME', 3),
  station(201, '용인휴게소(강릉)', 37.268, 127.228, [rest('용인', '하행', 2)], 2, 4),
  station(202, '용인휴게소(인천)', 37.272, 127.229, [rest('용인', '상행', 2)], 1, 4, 'ST'),
];

export const searchHandlers: Record<string, MockHandler> = {
  'GET routes': () => ({ routes: sampleRoutes }),
  'GET stations': ({ query = {} }) => {
    const limit = Math.min(Number(query.limit || 100), 500);
    let matches = sampleStations.filter(s => !query.chargerType || s.chargerTypes.includes(String(query.chargerType)));
    if (query.minLat !== undefined) matches = matches.filter(s => s.lat >= Number(query.minLat) && s.lat <= Number(query.maxLat) && s.lng >= Number(query.minLng) && s.lng <= Number(query.maxLng));
    const items = matches.map(s => ({ ...s, accesses: s.accesses.filter(a =>
      (!query.routeId || a.routeId === Number(query.routeId)) &&
      (!query.accessType || a.accessType === query.accessType) &&
      (!query.direction || a.accessType === 'IC' || a.direction === query.direction || a.direction === '양방향')),
    })).filter(s => s.accesses.length > 0).sort((a, b) => a.stationId - b.stationId);
    const stations = items.slice(0, limit).map(({ chargerTypes: _, ...s }) => { void _; return s; });
    return { count: stations.length, limit, truncated: items.length > limit, stations };
  },
};
