import { useCallback, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Button, Chip, EmptyState, ErrorState, LoadingState, StatusBadge } from '../../components/ui';
import { useAsync } from '../../hooks/useAsync';
import { statusTime } from '../search/searchUtils';
import { stationService } from './stationService';
import { localKst } from './stationUtils';
import type { ChargerHistory } from './contracts';

export function ChargerHistoryPage() {
  const { stationId, chargerId: rawId } = useParams();
  const chargerId = Number(rawId);
  const [days, setDays] = useState(1);
  return <div className="stack history-page"><div><Link className="text-link" to={`/stations/${stationId}`}>← 충전소 상세</Link><h1>충전기 상태 이력</h1><p className="small muted">충전기 {rawId} · 상태 변경 시각 기준</p></div>
    <div className="row wrap">{[1, 3, 7].map(value => <Chip key={value} selected={days === value} onClick={() => setDays(value)}>최근 {value === 1 ? '24시간' : `${value}일`}</Chip>)}</div>
    {!Number.isSafeInteger(chargerId) || chargerId < 1 ? <EmptyState title="충전기를 다시 선택해 주세요" /> : <HistoryResults key={`${chargerId}-${days}`} chargerId={chargerId} days={days} />}
  </div>;
}

function HistoryResults({ chargerId, days }: { chargerId: number; days: number }) {
  const [anchor] = useState(() => Date.now());
  const from = localKst(new Date(anchor - days * 86400000));
  const to = localKst(new Date(anchor));
  const loader = useCallback((signal: AbortSignal) => stationService.history({ chargerId, from, to, size: 20 }, signal), [chargerId, from, to]);
  const result = useAsync(loader);
  const [extra, setExtra] = useState<ChargerHistory['items']>([]);
  const [cursor, setCursor] = useState<string | null | undefined>(undefined);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState('');
  if (result.status === 'loading') return <LoadingState label="상태 이력을 불러오고 있습니다." />;
  if (result.status === 'error') return <ErrorState message={result.error.message} onRetry={result.retry} />;
  const items = [...result.data.items, ...extra];
  const next = cursor === undefined ? result.data.nextCursor : cursor;
  async function loadMore() {
    if (!next || loadingMore) return;
    setLoadingMore(true); setMoreError('');
    try {
      const page = await stationService.history({ chargerId, from, to, size: 20, cursor: next });
      setExtra(previous => [...previous, ...page.items]); setCursor(page.nextCursor);
    } catch (error) { setMoreError(error instanceof Error ? error.message : '추가 이력을 불러오지 못했습니다.'); }
    finally { setLoadingMore(false); }
  }
  return <div className="stack">
    {items.length === 0 ? <EmptyState title="이 기간에 상태 변경 이력이 없어요" description="기간을 늘려 다시 확인해 보세요." /> : <ol className="history-list">{items.map((item, index) => <li key={`${item.statusUpdatedAt}-${index}`} className="card"><div className="row between"><StatusBadge status={item.normalizedStatus} /><time dateTime={item.statusUpdatedAt}>{item.statusUpdatedAt.slice(0, 10)} {statusTime(item.statusUpdatedAt)}</time></div><p className="small muted">원천 상태 코드 {item.statusCode} · {item.sourceApi === 'STATUS' ? '상태 API' : '기본 정보 API'} 수집</p></li>)}</ol>}
    {moreError && <p role="alert" className="small field__error">{moreError}</p>}
    {next && <Button variant="secondary" onClick={() => void loadMore()} loading={loadingMore}>이력 더 보기</Button>}
    <p className="search-footnote">이력은 상태 변경 기록입니다. 조회 기간은 최대 7일이며, 서버에 남아 있는 기록만 표시합니다.</p>
  </div>;
}
