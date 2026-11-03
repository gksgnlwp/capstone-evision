import { useCallback } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Badge, EmptyState, ErrorState, LoadingState } from '../../components/ui';
import { config } from '../../config/env';
import { useAsync } from '../../hooks/useAsync';
import { useLocationAccess } from '../search/LocationContext';
import { demoPosition, queryFromParams } from './queryState';
import { recommendationService } from './recommendationService';
import { accessLabel, arrivalLabel, basisLabel, externalDirectionsUrl, probabilityLabel, reasonLabel } from './recommendationUtils';
import type { RecommendationItem, RecommendationQuery } from './contracts';
import { RecommendationMap } from './RecommendationMap';

export function ComparePage() {
  const [params] = useSearchParams();
  const location = useLocationAccess();
  const position = location.position ?? (config.environment === 'DEV' && params.get('demo') === '1' ? demoPosition : null);
  const query = queryFromParams(params, position);
  const ids = params.get('ids')?.split(',').map(Number).filter(Number.isSafeInteger) ?? [];
  if (!query || ids.length !== 2 || ids[0] === ids[1]) return <div className="stack"><h1>추천 비교</h1><EmptyState title="비교할 충전소를 다시 선택해 주세요" description="추천 결과에서 두 곳을 선택하면 항목별로 비교할 수 있어요." action={<Link className="button button--secondary" to={`/recommendations?${params}`}>추천 결과로 돌아가기</Link>} /></div>;
  return <CompareResults query={query} ids={ids} params={params} />;
}

function CompareResults({ query, ids, params }: { query: RecommendationQuery; ids: number[]; params: URLSearchParams }) {
  const { position, routeId, direction, remainingRangeKm, chargerType } = query;
  const loader = useCallback((signal: AbortSignal) => recommendationService.getRecommendations({ position, routeId, direction, remainingRangeKm, chargerType, limit: 5 }, signal), [position, routeId, direction, remainingRangeKm, chargerType]);
  const result = useAsync(loader);
  if (result.status === 'loading') return <LoadingState label="비교 정보를 불러오고 있습니다." />;
  if (result.status === 'error') return <ErrorState message={result.error.message} onRetry={result.retry} />;
  const items = ids.map(id => result.data.recommendations.find(item => item.stationId === id)).filter((item): item is RecommendationItem => !!item);
  if (items.length !== 2) return <EmptyState title="추천 결과가 달라졌어요" description="추천 결과를 다시 조회하고 비교할 두 곳을 선택해 주세요." action={<Link className="button button--secondary" to={`/recommendations?${params}`}>추천 결과로 돌아가기</Link>} />;
  const rows: { label: string; value: (item: RecommendationItem) => string }[] = [
    { label: '접근', value: accessLabel },
    { label: '이동 거리', value: item => `약 ${item.distanceKm.toFixed(1)}km` },
    { label: '우회거리', value: item => item.access.accessType === 'REST_AREA' ? '없음' : `약 ${item.detourKm.toFixed(1)}km` },
    { label: '도착 예정', value: item => `${arrivalLabel(item.eta)} · 약 ${item.etaMinutes}분` },
    { label: '이용 확률', value: item => probabilityLabel(item.availabilityProbability) },
    { label: '예상 대기', value: () => '정보 없음' },
    { label: '급속 충전기', value: item => `${item.fastChargerCount}대` },
    { label: '현재 가용', value: item => `${item.availableCount}대` },
    { label: '예측 근거', value: basisLabel },
  ];
  return <div className="stack compare-page">
    <div><Link className="text-link" to={`/recommendations?${params}`}>← 추천 결과</Link><h1>추천 비교</h1><p className="small muted">선택한 2곳 · 서버 추정 도착시각 기준</p></div>
    <div className="compare-table-wrap"><table className="compare-table"><thead><tr><th scope="col">항목</th>{items.map(item => <th key={item.stationId} scope="col"><span className="compare-rank">{item.rank}위</span>{item.name}</th>)}</tr></thead><tbody>{rows.map(row => <tr key={row.label}><th scope="row">{row.label}</th>{items.map(item => <td key={item.stationId}>{row.value(item)}</td>)}</tr>)}</tbody></table></div>
    <section className="card stack"><h2>추천 판단 정보</h2>{items.map(item => <p key={item.stationId} className="small"><Badge tone={item.access.accessType === 'REST_AREA' ? 'success' : 'info'}>{item.rank}위</Badge> {reasonLabel(item)}</p>)}</section>
    <RecommendationMap items={items} />
    <p className="search-footnote">이동 거리는 서버의 추정 도로거리입니다. 예상 대기시간과 운영기관은 현재 추천 API 응답에 없어 정보 없음으로 표시합니다.</p>
    <div className="compare-actions">{items.map(item => <div key={item.stationId} className="card stack"><strong>{item.name}</strong><Link className="button button--secondary button--full" to={`/stations/${item.stationId}`}>상세 보기</Link><a className="button button--primary button--full" href={externalDirectionsUrl(item)} target="_blank" rel="noopener noreferrer">외부 지도로 길 안내</a></div>)}</div>
  </div>;
}
