const base = new URL(process.env.API_SMOKE_BASE_URL || 'http://127.0.0.1:8080/api/');
if (!['http:', 'https:'].includes(base.protocol)) throw new Error('API_SMOKE_BASE_URL은 HTTP URL이어야 합니다.');
if (!base.pathname.endsWith('/')) base.pathname += '/';

async function get(path, query = {}) {
  const url = new URL(path, base);
  for (const [key, value] of Object.entries(query)) url.searchParams.set(key, String(value));
  const response = await fetch(url, { headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(10_000) });
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
  return response.json();
}

function check(condition, message) {
  if (!condition) throw new Error(message);
}

try {
  const routes = await get('routes');
  check(Array.isArray(routes.routes), 'routes: routes 배열이 없습니다.');
  check(routes.routes.every(route => Number.isSafeInteger(route.routeId) && Array.isArray(route.directions)), 'routes: 노선 ID 또는 방향 형식이 다릅니다.');
  console.log(`GET /api/routes: OK (${routes.routes.length}개 노선)`);

  const operators = await get('operators');
  check(Array.isArray(operators.operators), 'operators: operators 배열이 없습니다.');
  check(operators.operators.every(operator => typeof operator.busiId === 'string' && typeof operator.name === 'string' && Number.isInteger(operator.stationCount)), 'operators: 기관 형식이 다릅니다.');
  console.log(`GET /api/operators: OK (${operators.operators.length}개 기관)`);

  if (routes.routes.length === 0) {
    console.log('GET /api/stations: 노선 데이터가 없어 건너뜀');
  } else {
    const stations = await get('stations', { routeId: routes.routes[0].routeId, limit: 100 });
    check(Array.isArray(stations.stations) && Number.isInteger(stations.count) && typeof stations.truncated === 'boolean', 'stations: 검색 응답 형식이 다릅니다.');
    check(stations.stations.every(station => Number.isSafeInteger(station.stationId) && Array.isArray(station.accesses) && Number.isInteger(station.fastChargerCount)), 'stations: 충전소 요약 형식이 다릅니다.');
    console.log(`GET /api/stations: OK (${stations.count}곳)`);

    if (stations.stations.length > 0) {
      const first = stations.stations[0];
      const detail = await get(`stations/${first.stationId}`);
      check(detail.stationId === first.stationId && Array.isArray(detail.chargers), 'station detail: 충전기 목록 형식이 다릅니다.');
      console.log(`GET /api/stations/{id}: OK (충전기 ${detail.chargers.length}대)`);

      const ids = stations.stations.slice(0, 2).map(station => station.stationId).join(',');
      const summaries = await get('stations/summaries', { ids });
      check(Array.isArray(summaries.items) && summaries.items.length === ids.split(',').length, 'station summaries: 요청한 충전소 요약이 누락되었습니다.');
      console.log(`GET /api/stations/summaries: OK (${summaries.items.length}곳)`);

      if (detail.chargers.length > 0) {
        const now = new Date();
        const kst = date => new Date(date.getTime() + 9 * 3600_000).toISOString().slice(0, 19);
        const history = await get(`chargers/${detail.chargers[0].chargerId}/history`, { from: kst(new Date(now.getTime() - 86400_000)), to: kst(now), size: 20 });
        check(Array.isArray(history.items) && history.chargerId === detail.chargers[0].chargerId, 'charger history: 응답 형식이 다릅니다.');
        console.log(`GET /api/chargers/{id}/history: OK (${history.items.length}건)`);
      }

      const recommendation = await get('recommendations', { lat: first.lat, lng: first.lng, routeId: routes.routes[0].routeId, remainingRangeKm: 200, limit: 5 });
      check(Array.isArray(recommendation.recommendations) && Number.isInteger(recommendation.candidateCount), 'recommendations: 추천 응답 형식이 다릅니다.');
      console.log(`GET /api/recommendations: OK (${recommendation.recommendations.length}곳)`);
    }
  }
} catch (error) {
  console.error(`공개 조회 API 연결 실패: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
}
