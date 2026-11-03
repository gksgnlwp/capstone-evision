import { useCallback } from 'react';
import { Link } from 'react-router-dom';
import { Star } from 'lucide-react';
import { Badge, Button, EmptyState, ErrorState, LoadingState } from '../../components/ui';
import { useAsync } from '../../hooks/useAsync';
import { parseKst, statusTime, statusTone } from '../search/searchUtils';
import { personalizationService } from './personalizationService';
import { favoriteLimit, removeFavorite, usePreferences } from './preferences';
import type { FavoriteSummary } from './contracts';

function accessSummary(item: FavoriteSummary) {
  const access = item.station?.accesses[0];
  if (!access) return '접근 정보 없음';
  if (access.accessType === 'IC') return `IC · ${access.icName || 'IC 인근'}${access.detourKm === null ? '' : ` 우회 약 ${access.detourKm.toFixed(1)}km`}`;
  return `휴게소 · ${access.routeName || '고속도로'}${access.direction ? ` ${access.direction} 방향` : ''}`;
}

export function FavoritesPage() {
  const { favoriteIds, favoritePersistent } = usePreferences();
  const idsKey = favoriteIds.join(',');
  const loader = useCallback(async (signal: AbortSignal) => {
    if (!idsKey) return { items: [] as FavoriteSummary[], receivedAt: Date.now() };
    const items = await personalizationService.favorites(idsKey.split(',').map(Number), signal);
    return { items, receivedAt: Date.now() };
  }, [idsKey]);
  const result = useAsync(loader);

  return <div className="stack favorites-page">
    <div><h1>즐겨찾기</h1><p className="small muted">{favoriteIds.length} / {favoriteLimit}개 · {favoritePersistent ? '이 브라우저에 저장' : '현재 접속 동안만 유지'}</p></div>
    {favoriteIds.length === 0 ? <EmptyState title="저장한 충전소가 없어요" description="충전소 상세 화면의 별표로 자주 가는 곳을 저장해 보세요." action={<Link className="button button--secondary" to="/route">충전소 찾기</Link>} /> : <>
      {result.status === 'loading' && <LoadingState label="즐겨찾기 상태를 불러오고 있습니다." />}
      {result.status === 'error' && <ErrorState message={result.error.message} onRetry={result.retry} />}
      {result.status === 'success' && <>
        <div className="row between favorites-refresh"><span className="small muted">현재 상태를 한 번에 불러왔어요 · {new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul' }).format(result.data.receivedAt)}</span><Button variant="ghost" onClick={result.retry}>새로고침</Button></div>
        <div className="favorites-list">{result.data.items.map(item => {
          const station = item.station;
          const active = item.status === 'ACTIVE' && station !== null;
          const updatedAt = parseKst(station?.latestStatusUpdatedAt ?? null);
          const delayed = active && updatedAt !== null && result.data.receivedAt - updatedAt.getTime() >= 10 * 60 * 1000;
          return <article className={`favorite-row ${active ? '' : 'favorite-row--closed'}`} key={item.stationId}>
            <div className="favorite-row__body">
              {active ? <Link to={`/stations/${item.stationId}`} className="favorite-row__name">{station.name}</Link> : <strong className="favorite-row__name">{station?.name || `충전소 ${item.stationId}`}</strong>}
              <span className="small muted">{active ? accessSummary(item) : item.status === 'CLOSED' ? '운영이 종료된 충전소입니다.' : '충전소 정보를 찾을 수 없습니다.'}</span>
            </div>
            {active ? <Badge tone={statusTone(station)}>{station.latestStatusUpdatedAt === null || station.unknownCount === station.fastChargerCount ? '상태 미확인' : `이용 가능 ${station.availableCount}/${station.fastChargerCount}`}</Badge> : <Badge tone="danger">{item.status === 'CLOSED' ? '운영 종료' : '정보 없음'}</Badge>}
            <button className={`favorite-remove ${active ? '' : 'favorite-remove--closed'}`} type="button" aria-label={`${station?.name || `충전소 ${item.stationId}`} 즐겨찾기 해제`} onClick={() => removeFavorite(item.stationId)}>{active ? <Star size={22} fill="currentColor" aria-hidden="true" /> : '삭제'}</button>
            {active && station.latestStatusUpdatedAt && <span className="favorite-row__time small muted">{statusTime(station.latestStatusUpdatedAt)} 갱신{delayed && <span className="favorite-delayed"> · 갱신 지연</span>}</span>}
          </article>;
        })}</div>
      </>}
    </>}
    <p className="personalization-note">{favoritePersistent ? '로그인 없이 이 브라우저에만 저장됩니다.' : '브라우저 저장소를 사용할 수 없어 현재 접속 동안만 유지됩니다. 새로고침하면 사라질 수 있어요.'} 최대 20개까지 추가할 수 있어요.</p>
    <p className="small muted">충전소 상세 화면의 ★로 추가·해제합니다.</p>
  </div>;
}
