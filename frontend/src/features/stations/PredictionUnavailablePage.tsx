import { useCallback } from 'react';
import { Link, useParams } from 'react-router-dom';
import { EmptyState, ErrorState, LoadingState, StatusBadge } from '../../components/ui';
import { useAsync } from '../../hooks/useAsync';
import { stationService } from './stationService';
import { detailStatusTime } from './stationUtils';

export function PredictionUnavailablePage() {
  const { stationId: rawId } = useParams();
  const stationId = Number(rawId);
  return <div className="stack"><div><Link className="text-link" to={`/stations/${rawId}`}>← 충전소 상세</Link><h1>예측 미제공 안내</h1></div>
    {!Number.isSafeInteger(stationId) || stationId < 1 ? <EmptyState title="충전소를 다시 선택해 주세요" /> : <CurrentStatus stationId={stationId} />}
  </div>;
}

function CurrentStatus({ stationId }: { stationId: number }) {
  const loader = useCallback((signal: AbortSignal) => stationService.detail(stationId, signal), [stationId]);
  const result = useAsync(loader);
  if (result.status === 'loading') return <LoadingState label="현재 상태를 불러오고 있습니다." />;
  if (result.status === 'error') return <ErrorState message={result.error.message} onRetry={result.retry} />;
  const fast = result.data.chargers.filter(charger => charger.fast);
  return <>
    <section className="card stack"><h2>도착 시점 예측 정보가 없어요</h2><p className="small muted">{result.data.name} · 예측값과 산출 시각을 조회할 수 없어 현재 충전기 상태를 대신 보여드립니다.</p></section>
    <section className="stack"><h2>현재 급속 충전기 상태</h2>{fast.length === 0 ? <EmptyState title="급속 충전기 정보가 없어요" /> : fast.map(charger => <div key={charger.chargerId} className="card row between"><div><strong>충전기 {charger.chgerId}</strong><p className="small muted">{detailStatusTime(charger.statusUpdatedAt)}</p></div><StatusBadge status={charger.normalizedStatus} /></div>)}</section>
    <p className="search-footnote">현재 상태와 도착 시점의 이용 가능성은 다릅니다. 갱신 시각이 오래됐거나 상태가 미확인이면 이용 가능으로 판단하지 마세요.</p>
  </>;
}
