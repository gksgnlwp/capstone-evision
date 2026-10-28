import { useCallback } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { RefreshCw, Route as RouteIcon } from 'lucide-react';
import { Button, EmptyState, ErrorState, LoadingState } from '../../components/ui';
import { useAsync } from '../../hooks/useAsync';
import { searchService } from './searchService';
import { useLocationAccess } from './LocationContext';
import { SearchFilters, type AccessFilter } from './SearchFilters';
import { StationCard } from './StationCard';
import { sortStations } from './searchUtils';
import type { SearchQuery } from './contracts';

const loadRoutes = (signal: AbortSignal) => searchService.getRoutes(signal);

export function RouteSearchPage() {
  const [params, setParams] = useSearchParams();
  const routes = useAsync(loadRoutes);
  const { position } = useLocationAccess();
  const rawId = params.get('routeId');
  const route = routes.data?.find(r => String(r.routeId) === rawId);
  const direction = route?.directions.includes(params.get('direction') || '') ? params.get('direction') || '' : '';
  const accessType = (['REST_AREA', 'IC'].includes(params.get('accessType') || '') ? params.get('accessType') : '') as AccessFilter;
  const chargerType = params.get('chargerType') || '';
  const destination = params.get('destination')?.slice(0, 100);

  function change(values: Record<string, string>) {
    setParams(previous => { const next = new URLSearchParams(previous); for (const [key, value] of Object.entries(values)) { if (value) next.set(key, value); else next.delete(key); } return next; });
  }

  return <div className="stack route-page">
    <div><h1>경로 위 충전소</h1>{destination && <p className="small muted">목적지 {destination} · 이용할 노선과 방향을 골라 주세요.</p>}</div>
    <section className="route-selection stack" aria-label="노선과 방향 선택">
      {routes.status === 'loading' && <LoadingState label="고속도로 노선을 불러오고 있습니다." />}
      {routes.status === 'error' && <ErrorState message={routes.error.message} onRetry={routes.retry} />}
      {routes.status === 'success' && <>
        <div className="select-field"><label htmlFor="route-select">고속도로 노선</label><select id="route-select" value={route?.routeId || ''} onChange={event => change({ routeId: event.target.value, direction: '', limit: '' })}>
          <option value="">노선 선택</option>{routes.data.map(r => <option key={r.routeId} value={r.routeId}>{r.routeName} · {r.routeNo}번</option>)}
        </select></div>
        {rawId && !route && <p className="small field__error">선택한 노선이 없습니다. 다시 선택해 주세요.</p>}
        {route && <>
          <div className="select-field"><label htmlFor="direction-select">진행 방향</label><select id="direction-select" value={direction} onChange={event => change({ direction: event.target.value, limit: '' })}><option value="">방향 전체</option>{route.directions.map(value => <option key={value}>{value}</option>)}</select></div>
          <p className="small muted">매핑된 휴게소 {route.restAreaStations}곳 · IC 인근 {route.icStations}곳</p>
        </>}
        {routes.data.length === 0 && <EmptyState title="조회할 노선이 없어요" description="충전소가 연결된 노선이 준비되면 표시됩니다." />}
      </>}
    </section>
    {route ? <>
      <SearchFilters accessType={accessType} chargerType={chargerType} onAccessChange={value => change({ accessType: value, limit: '' })} onChargerChange={value => change({ chargerType: value, limit: '' })} />
      <RouteResults query={{ routeId: route.routeId, direction: direction || undefined, accessType: accessType || undefined, chargerType: chargerType || undefined, limit: Math.min(500, Math.max(100, Number(params.get('limit')) || 100)) }} onMore={limit => change({ limit: String(limit) })} />
      <Link to={`/recommendations?${params}`} className="button button--primary button--full">도착 시점 기준으로 추천 받기</Link>
      <p className="search-footnote">방향은 휴게소에 적용하며, 같은 노선의 IC는 함께 표시합니다. 대수는 급속충전기 전체 기준입니다. IC 우회거리는 매핑된 값으로, 실제 도로 주행거리와 다를 수 있습니다.{position && ' 충전소 정렬도 현재 위치에서의 직선거리 기준입니다.'}</p>
    </> : routes.status === 'success' && <div className="route-onboarding"><RouteIcon size={32} aria-hidden="true" /><h2>어느 고속도로를 이용하시나요?</h2><p>노선과 방향을 선택하면 휴게소와 IC 인근 충전소를 함께 볼 수 있어요.</p><p className="small muted">목적지 기반 자동 경로 선택은 준비 중입니다.</p></div>}
  </div>;
}

function RouteResults({ query, onMore }: { query: SearchQuery; onMore: (limit: number) => void }) {
  const { position } = useLocationAccess();
  const { routeId, direction, accessType, chargerType, limit } = query;
  const loader = useCallback((signal: AbortSignal) => searchService.search({ routeId, direction, accessType, chargerType, limit }, signal), [routeId, direction, accessType, chargerType, limit]);
  const result = useAsync(loader);
  if (result.status === 'loading') return <LoadingState />;
  if (result.status === 'error') return <ErrorState message={result.error.message} onRetry={result.retry} />;
  return <div className="stack">
    <div className="row between"><h2>{position ? '가까운 충전소' : '충전소 목록'} <span className="result-count">{result.data.count}</span></h2><Button variant="ghost" className="compact-button" onClick={result.retry} aria-label="충전소 새로고침"><RefreshCw size={16} /></Button></div>
    {result.data.count === 0 ? <EmptyState title="조건에 맞는 충전소가 없어요" description="접근 유형이나 충전기 유형을 바꿔 보세요." /> : <div className="station-list">{sortStations(result.data.stations, position).map(station => <StationCard key={station.stationId} station={station} origin={position} receivedAt={result.data.receivedAt} />)}</div>}
    {result.data.truncated && (result.data.limit < 500 ? <Button variant="secondary" onClick={() => onMore(Math.min(500, result.data.limit + 100))}>충전소 더 보기</Button> : <p className="small muted">최대 500곳을 표시했습니다. 필터로 범위를 좁혀 주세요.</p>)}
  </div>;
}
