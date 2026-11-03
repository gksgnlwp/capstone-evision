import { useCallback, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { MapPin, RefreshCw } from 'lucide-react';
import { Badge, Button, EmptyState, ErrorState, LoadingState } from '../../components/ui';
import { config } from '../../config/env';
import { useAsync } from '../../hooks/useAsync';
import { useLocationAccess } from '../search/LocationContext';
import { searchService } from '../search/searchService';
import { recommendationService } from './recommendationService';
import { accessLabel, arrivalLabel, basisLabel, probabilityLabel, reasonLabel } from './recommendationUtils';
import { demoPosition, queryFromParams } from './queryState';
import type { RecommendationItem, RecommendationQuery } from './contracts';

const loadRoutes = (signal: AbortSignal) => searchService.getRoutes(signal);

export function RecommendationPage() {
  const [params, setParams] = useSearchParams();
  const routes = useAsync(loadRoutes);
  const location = useLocationAccess();
  const [selected, setSelected] = useState<number[]>([]);
  const demo = config.environment === 'DEV' && params.get('demo') === '1';
  const position = location.position ?? (demo ? demoPosition : null);
  const route = routes.data?.find(r => r.routeId === Number(params.get('routeId')));
  const query = route ? queryFromParams(params, position) : null;
  const direction = route?.directions.includes(params.get('direction') || '') ? params.get('direction') || '' : '';

  function change(values: Record<string, string>) {
    setSelected([]);
    setParams(previous => { const next = new URLSearchParams(previous); for (const [key, value] of Object.entries(values)) { if (value) next.set(key, value); else next.delete(key); } return next; });
  }
  function toggle(id: number) {
    setSelected(previous => previous.includes(id) ? previous.filter(value => value !== id) : previous.length < 2 ? [...previous, id] : [previous[1], id]);
  }

  return <div className="stack recommendation-page">
    <div><h1>충전소 추천</h1><p className="small muted">이용 가능 확률과 우회거리 등을 종합한 추천 순서입니다.</p></div>
    <section className="card stack" aria-label="추천 조건">
      {params.get('destination') && <p className="small muted">목적지 {params.get('destination')}</p>}
      {routes.status === 'loading' && <LoadingState label="노선을 불러오고 있습니다." />}
      {routes.status === 'error' && <ErrorState message={routes.error.message} onRetry={routes.retry} />}
      {routes.status === 'success' && <>
        <div className="select-field"><label htmlFor="recommend-route">고속도로 노선</label><select id="recommend-route" value={route?.routeId ?? ''} onChange={event => change({ routeId: event.target.value, direction: '' })}><option value="">노선 선택</option>{routes.data.map(r => <option key={r.routeId} value={r.routeId}>{r.routeName} · {r.routeNo}번</option>)}</select></div>
        {route && <div className="select-field"><label htmlFor="recommend-direction">진행 방향</label><select id="recommend-direction" value={direction} onChange={event => change({ direction: event.target.value })}><option value="">방향 전체</option>{route.directions.map(value => <option key={value}>{value}</option>)}</select></div>}
      </>}
      <div className="field"><label htmlFor="remaining-range">남은 주행 가능 거리 (km)</label><input id="remaining-range" type="number" min="1" max="1000" step="1" inputMode="decimal" placeholder="예: 150" value={params.get('remainingRangeKm') || ''} onChange={event => change({ remainingRangeKm: event.target.value })} /></div>
      {!position && <div className="recommend-location"><MapPin size={18} aria-hidden="true" /><p className="small">추천에는 현재 위치가 필요해요. 위치는 브라우저 메모리에만 유지합니다.</p><Button variant="secondary" onClick={() => void location.request()} loading={location.status === 'loading'}>현재 위치 확인</Button>{config.environment === 'DEV' && <Button variant="ghost" onClick={() => change({ demo: '1' })}>시연 위치 사용</Button>}</div>}
      {position && <p className="small muted">{demo && !location.position ? '시연 위치 적용' : '현재 위치 확인됨'} · 도착 시각은 서버가 거리로 계산합니다.</p>}
      {location.message && !position && <p role="alert" className="small field__error">{location.message}</p>}
    </section>
    {query ? <RecommendationResults query={{ ...query, direction: direction || undefined }} params={params} selected={selected} onToggle={toggle} />
      : <EmptyState title="추천 조건을 입력해 주세요" description="노선, 남은 주행 가능 거리와 현재 위치를 확인하면 추천 결과가 표시됩니다." action={<Link className="text-link" to="/route">경로 위 충전소 보기</Link>} />}
  </div>;
}

function RecommendationResults({ query, params, selected, onToggle }: { query: RecommendationQuery; params: URLSearchParams; selected: number[]; onToggle: (id: number) => void }) {
  const { position, routeId, direction, remainingRangeKm, chargerType } = query;
  const loader = useCallback((signal: AbortSignal) => recommendationService.getRecommendations({ position, routeId, direction, remainingRangeKm, chargerType, limit: 5 }, signal), [position, routeId, direction, remainingRangeKm, chargerType]);
  const result = useAsync(loader);
  if (result.status === 'loading') return <LoadingState label="추천 충전소를 찾고 있습니다." />;
  if (result.status === 'error') return <ErrorState message={result.error.message} onRetry={result.retry} />;
  const compareParams = new URLSearchParams(params);
  compareParams.set('ids', selected.join(','));
  return <div className="stack">
    <div className="row between"><h2>추천 상위 {result.data.recommendations.length}곳</h2><Button variant="ghost" className="compact-button" onClick={result.retry} aria-label="추천 결과 새로고침"><RefreshCw size={16} /></Button></div>
    <div className="recommend-summary card"><span>추천 가능한 거리 약 {result.data.reachableKm.toFixed(1)}km</span><span>후보 {result.data.candidateCount}곳 · 범위 밖 {result.data.unreachableCount}곳</span></div>
    <Link className="recommend-arrival card" to={`/recommendations/arrival?${params}`}><span>도착 예정시각 변경</span><strong>현재 서버 추정 시각 기준</strong></Link>
    {result.data.recommendations.length === 0 ? <EmptyState title="추천 가능한 충전소가 없어요" description="현재 위치, 노선 또는 남은 주행 가능 거리를 확인해 주세요." />
      : <div className="station-list">{result.data.recommendations.map(item => <RecommendationCard key={item.stationId} item={item} selected={selected.includes(item.stationId)} onToggle={() => onToggle(item.stationId)} />)}</div>}
    {selected.length === 2 && <Link className="button button--primary button--full" to={`/compare?${compareParams}`}>선택한 2곳 비교하기</Link>}
    <p className="search-footnote">추천 순위는 이용 가능 확률·우회거리·출력·충전기 수·주행 가능 범위를 함께 반영합니다. 도로거리는 직선거리 보정에 따른 추정치이며, 대기시간은 현재 API에서 제공하지 않습니다.</p>
  </div>;
}

function RecommendationCard({ item, selected, onToggle }: { item: RecommendationItem; selected: boolean; onToggle: () => void }) {
  return <article className="recommend-card card">
    <div className="row between"><span className="recommend-rank">{item.rank}</span><label className="recommend-compare"><input type="checkbox" checked={selected} onChange={onToggle} aria-label={`${item.name} 비교 선택`} /> 비교</label></div>
    <h3>{item.name}</h3>
    <div className="row wrap"><Badge tone={item.access.accessType === 'REST_AREA' ? 'success' : 'info'}>{accessLabel(item)}</Badge><Badge tone={item.basis === 'AI_FORECAST' ? 'success' : 'neutral'}>{basisLabel(item)}</Badge></div>
    <div className="recommend-metrics"><div><span>이동</span><strong>약 {item.distanceKm.toFixed(1)}km</strong></div><div><span>도착</span><strong>{arrivalLabel(item.eta)}</strong></div><div><span>이용 확률</span><strong>{probabilityLabel(item.availabilityProbability)}</strong></div><div><span>예상 대기</span><strong>정보 없음</strong></div></div>
    <p className="recommend-reason">추천 판단 정보 · {reasonLabel(item)}</p>
    <Link className="text-link" to={`/stations/${item.stationId}`}>충전소 상세 보기</Link>
  </article>;
}
