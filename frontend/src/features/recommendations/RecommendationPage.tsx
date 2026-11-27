import { useCallback, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { MapPin, RefreshCw } from 'lucide-react';
import { Badge, Button, EmptyState, ErrorState, LoadingState } from '../../components/ui';
import { config } from '../../config/env';
import { useAsync } from '../../hooks/useAsync';
import { useLocationAccess } from '../search/LocationContext';
import { usePreferences } from '../personalization/preferences';
import { recommendationService } from './recommendationService';
import { accessLabel, arrivalLabel, basisLabel, groupRecommendations, operatorLabel, probabilityLabel, reasonLabel, sortRecommendations, type RecommendationSort } from './recommendationUtils';
import { demoPosition, destinationFromParams, queryFromParams } from './queryState';
import type { RecommendationItem, RecommendationQuery } from './contracts';
import { DestinationSearch } from './DestinationSearch';
import { RecommendationRoute } from './RecommendationRoute';

export function RecommendationPage() {
  const [params, setParams] = useSearchParams();
  const location = useLocationAccess();
  const [selected, setSelected] = useState<number[]>([]);
  const demo = config.environment === 'DEV' && params.get('demo') === '1';
  const position = location.position ?? (demo ? demoPosition : null);
  const destination = destinationFromParams(params);
  const destinationText = params.get('destination')?.slice(0, 100) || '';
  const query = useMemo(() => queryFromParams(params, position), [params, position]);

  function change(values: Record<string, string>) {
    setSelected([]);
    setParams(previous => {
      const next = new URLSearchParams(previous);
      for (const key of ['routeId', 'direction', 'ids']) next.delete(key);
      for (const [key, value] of Object.entries(values)) {
        if (value) next.set(key, value);
        else next.delete(key);
      }
      return next;
    });
  }
  function toggle(id: number) {
    setSelected(previous => previous.includes(id) ? previous.filter(value => value !== id) : previous.length < 2 ? [...previous, id] : [previous[1], id]);
  }

  return <div className="stack recommendation-page">
    <div><h1>충전소 추천</h1><p className="small muted">이용 가능 확률과 우회거리 등을 종합한 추천 순서입니다.</p></div>
    <section className="card stack" aria-label="추천 조건">
      {destination
        ? <div className="row between wrap">
          <div><p className="small muted">목적지</p><strong>{destination.name}</strong></div>
          <Button type="button" variant="secondary" onClick={() => change({ destLat: '', destLng: '' })}>목적지 변경</Button>
        </div>
        : <DestinationSearch key={destinationText} initialValue={destinationText}
          onSelect={place => change({ destination: place.name, destLat: String(place.lat), destLng: String(place.lng) })} />}
      <div className="field"><label htmlFor="remaining-range">남은 주행 가능 거리 (km)</label><input id="remaining-range" type="number" min="1" max="1000" step="1" inputMode="decimal" placeholder="예: 150" value={params.get('remainingRangeKm') || ''} onChange={event => change({ remainingRangeKm: event.target.value })} /></div>
      {!position && <div className="recommend-location"><MapPin size={18} aria-hidden="true" /><p className="small">추천에는 현재 위치가 필요해요. 위치는 브라우저 메모리에만 유지합니다.</p><Button variant="secondary" onClick={() => void location.request()} loading={location.status === 'loading'}>현재 위치 확인</Button>{config.environment === 'DEV' && <Button variant="ghost" onClick={() => change({ demo: '1' })}>시연 위치 사용</Button>}</div>}
      {position && <p className="small muted">{demo && !location.position ? '시연 위치 적용' : '현재 위치 확인됨'} · 도착 시각은 서버가 거리로 계산합니다.</p>}
      {location.message && !position && <p role="alert" className="small field__error">{location.message}</p>}
    </section>
    {query ? <RecommendationResults query={query} params={params} selected={selected} onToggle={toggle} />
      : <EmptyState title="추천 조건을 입력해 주세요" description="목적지를 선택하고 남은 주행 가능 거리와 현재 위치를 확인하면 추천 결과가 표시됩니다." action={<Link className="text-link" to="/route">노선별 충전소 보기</Link>} />}
  </div>;
}

function RecommendationResults({ query, params, selected, onToggle }: { query: RecommendationQuery; params: URLSearchParams; selected: number[]; onToggle: (id: number) => void }) {
  const [sort, setSort] = useState<RecommendationSort>('score');
  const loader = useCallback((signal: AbortSignal) => recommendationService.getRecommendations(query, signal), [query]);
  const result = useAsync(loader);
  if (result.status === 'loading') return <LoadingState label="추천 충전소를 찾고 있습니다." />;
  if (result.status === 'error') return <ErrorState message={result.error.message} onRetry={result.retry} />;
  if (!result.data.route) return <EmptyState title="이동 경로의 고속도로를 찾지 못했어요"
    description={config.environment === 'DEV' ? 'DEV에서는 시연 목적지를 선택하면 추천 예시를 볼 수 있어요.' : '현재 위치와 목적지를 확인하거나 다른 목적지를 선택해 주세요.'} />;
  const compareParams = new URLSearchParams(params);
  compareParams.set('ids', selected.join(','));
  const groups = groupRecommendations(sortRecommendations(result.data.recommendations, sort));
  return <div className="stack">
    <div className="row between"><h2>추천 상위 {result.data.recommendations.length}곳</h2><Button variant="ghost" className="compact-button" onClick={result.retry} aria-label="추천 결과 새로고침"><RefreshCw size={16} /></Button></div>
    <div className="recommend-summary"><strong>주행 가능 범위 약 {result.data.reachableKm.toFixed(1)}km</strong><span>후보 {result.data.candidateCount}곳 · 범위 밖 {result.data.unreachableCount}곳</span></div>
    <RecommendationRoute route={result.data.route} />
    <Link className="recommend-arrival" to={`/recommendations/arrival?${params}`}><span>도착 예정시각 변경</span><strong>{params.get('arrivalOffsetMin') ? `선택한 ${params.get('arrivalOffsetMin')}분 후 · 추천 미적용` : '서버 추정 시각 기준'}</strong></Link>
    {result.data.recommendations.length > 0 && <div className="recommend-sort" role="group" aria-label="추천 결과 정렬"><button type="button" aria-pressed={sort === 'score'} onClick={() => setSort('score')}>빈자리 우선</button><button type="button" aria-pressed={sort === 'distance'} onClick={() => setSort('distance')}>가까운 순</button></div>}
    {result.data.recommendations.length === 0 ? <EmptyState title="추천 가능한 충전소가 없어요" description="선택된 노선에 현재 조건으로 도달할 수 있는 충전소가 없어요. 남은 주행 가능 거리를 확인해 주세요." />
      : <div className="station-list">{groups.map(group => group.items.length > 1 ? <section key={group.key} className="recommend-group" aria-label={`${group.label} 충전소 ${group.items.length}곳`}><div className="recommend-group__heading"><h3>{group.label}</h3><span>운영기관별 {group.items.length}곳</span></div>{group.items.map(item => <RecommendationCard key={item.stationId} item={item} selected={selected.includes(item.stationId)} onToggle={() => onToggle(item.stationId)} />)}</section> : <RecommendationCard key={group.key} item={group.items[0]} selected={selected.includes(group.items[0].stationId)} onToggle={() => onToggle(group.items[0].stationId)} />)}</div>}
    {selected.length === 2 && <Link className="button button--primary button--full" to={`/compare?${compareParams}`}>선택한 2곳 비교하기</Link>}
    <p className="search-footnote">빈자리 우선은 서버의 추천 점수 순서로, 이용 가능 확률과 우회거리·출력·충전기 수·주행 가능 범위를 함께 반영합니다. 정상 급속충전기가 없는 충전소는 추천 후보에서 제외됩니다. 가까운 순은 서버가 계산한 추정 도로거리 기준입니다. 대기시간은 현재 API에서 제공하지 않습니다.</p>
  </div>;
}

function RecommendationCard({ item, selected, onToggle }: { item: RecommendationItem; selected: boolean; onToggle: () => void }) {
  const { operatorIds } = usePreferences();
  return <article className={`recommend-card card ${operatorIds.includes(item.busiId) ? 'recommend-card--preferred' : ''}`}>
    <div className="row between"><span className="recommend-rank" aria-label={`추천 ${item.rank}위`}>{item.rank}순위</span><label className="recommend-compare"><input type="checkbox" checked={selected} onChange={onToggle} aria-label={`${item.name} 비교 선택`} /> 비교</label></div>
    <h3>{item.name}</h3>
    <p className="recommend-card__context">{accessLabel(item)} · {operatorLabel(item)}{operatorIds.includes(item.busiId) && ' · 선호 운영기관'}</p>
    <div className="recommend-metrics"><div><span>도착 시 이용 확률</span><strong>{probabilityLabel(item.availabilityProbability)}</strong></div><div><span>현재 위치에서</span><strong>약 {item.distanceKm.toFixed(1)}km</strong></div><div><span>도착 예상</span><strong>{arrivalLabel(item.eta)}</strong></div><div><span>예상 대기</span><strong>정보 없음</strong></div></div>
    <div className="recommend-card__basis"><Badge tone={item.basis === 'AI_FORECAST' ? 'success' : 'neutral'}>{basisLabel(item)}</Badge></div>
    <details className="recommend-reason"><summary>추천 기준 보기</summary><p>{reasonLabel(item)}</p></details>
    <Link className="text-link" to={`/stations/${item.stationId}`}>충전소 상세 보기</Link>
  </article>;
}
