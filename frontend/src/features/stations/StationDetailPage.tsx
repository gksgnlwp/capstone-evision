import { useCallback, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { MapPin, Phone, RefreshCw, Star } from 'lucide-react';
import { Badge, Button, EmptyState, ErrorState, LoadingState, StatusBadge } from '../../components/ui';
import { useAsync } from '../../hooks/useAsync';
import { stationService } from './stationService';
import { chargerTypeLabel, detailAccessLabel, detailStatusTime } from './stationUtils';
import { toggleFavorite, usePreferences } from '../personalization/preferences';
import { collectionTime, isCollectionDelayed } from '../search/searchUtils';

export function StationDetailPage() {
  const { stationId: rawId } = useParams();
  const stationId = Number(rawId);
  return <div className="stack station-detail-page"><h1>충전소 상세</h1>
    {!Number.isSafeInteger(stationId) || stationId < 1 ? <EmptyState title="충전소를 다시 선택해 주세요" action={<Link className="text-link" to="/route">충전소 목록으로</Link>} /> : <DetailContent stationId={stationId} />}
  </div>;
}

function DetailContent({ stationId }: { stationId: number }) {
  const { favoriteIds, favoritePersistent } = usePreferences();
  const [favoriteMessage, setFavoriteMessage] = useState('');
  const [favoriteLimitReached, setFavoriteLimitReached] = useState(false);
  const isFavorite = favoriteIds.includes(stationId);
  function onFavorite() {
    const result = toggleFavorite(stationId);
    setFavoriteLimitReached(result === 'limit');
    setFavoriteMessage(result === 'limit' ? '즐겨찾기는 최대 20개까지 저장할 수 있어요.' : result === 'added' ? '즐겨찾기에 추가했어요.' : '즐겨찾기에서 해제했어요.');
  }
  const loader = useCallback((signal: AbortSignal) => stationService.detail(stationId, signal), [stationId]);
  const result = useAsync(loader);
  if (result.status === 'loading') return <LoadingState label="충전소 정보를 불러오고 있습니다." />;
  if (result.status === 'error') return <ErrorState message={result.error.message} onRetry={result.retry} />;
  const station = result.data;
  const fast = station.chargers.filter(charger => charger.fast);
  const available = fast.filter(charger => charger.reliability === 'NORMAL' && charger.normalizedStatus === 'AVAILABLE').length;
  const charging = fast.filter(charger => charger.reliability === 'NORMAL' && charger.normalizedStatus === 'CHARGING').length;
  const unavailable = fast.filter(charger => charger.reliability === 'FAULT').length;
  const unknown = fast.filter(charger => charger.reliability === 'UNKNOWN').length;
  const suspect = fast.filter(charger => charger.reliability === 'SUSPECT').length;
  const delayed = isCollectionDelayed(station.statusCollectedAt);
  const sortedFast = [...fast].sort((a, b) => Number(a.reliability !== 'NORMAL') - Number(b.reliability !== 'NORMAL'));
  const segments = [
    { key: 'available', label: '이용 가능', count: available },
    { key: 'charging', label: '충전 중', count: charging },
    { key: 'unavailable', label: '이용 불가', count: unavailable },
    { key: 'suspect', label: '상태 의심', count: suspect },
    { key: 'unknown', label: '미확인', count: unknown },
  ];
  return <>
    <section className="card stack"><div className="row between"><div><h2>{station.name}</h2></div><div className="row"><button type="button" className={`station-favorite ${isFavorite ? 'station-favorite--active' : ''}`} aria-pressed={isFavorite} aria-label={isFavorite ? '즐겨찾기 해제' : '즐겨찾기 추가'} onClick={onFavorite}><Star size={21} fill={isFavorite ? 'currentColor' : 'none'} aria-hidden="true" /></button><Button variant="ghost" className="compact-button" onClick={result.retry} aria-label="충전소 정보 새로고침"><RefreshCw size={18} /></Button></div></div>
      {favoriteMessage && <p role="status" className="small muted">{favoriteMessage} {favoriteLimitReached && <Link className="text-link" to="/favorites">즐겨찾기 정리하기</Link>}</p>}
      {!favoritePersistent && <p className="small muted">브라우저 저장소를 사용할 수 없어 즐겨찾기는 현재 접속 동안만 유지됩니다. 새로고침하면 사라질 수 있어요.</p>}
      <p className="small muted"><MapPin size={14} aria-hidden="true" /> {station.address || '주소 정보 없음'} {station.addressDetail || ''}</p>
      <div className="row wrap">{station.accesses.map((access, index) => <Badge key={`${access.accessType}-${access.restAreaId || access.icId}-${index}`} tone={access.accessType === 'REST_AREA' ? 'success' : 'info'}>{detailAccessLabel(access)}</Badge>)}</div>
    </section>
    <section className="card stack" aria-label="충전기 상태"><div className="row between wrap"><h2>급속충전기 상태</h2><strong className="charger-summary-count">이용 가능 {available} / {fast.length}대</strong></div>
      {station.reliability === 'ALL_ERROR' && <div className="charger-alert charger-alert--warning" role="alert"><strong>정상으로 확인된 급속충전기가 없어요.</strong><span>의심·고장·점검·미확인 충전기는 이용 가능 대수에서 제외했습니다. 다른 충전소도 확인해 주세요.</span><Link className="text-link" to="/route">다른 충전소 찾기</Link></div>}
      {delayed && <div className="charger-alert charger-alert--warning" role="status"><strong>상태 수집 지연</strong><span>마지막으로 성공한 상태 수집이 10분 이상 지났습니다. 현장 상태와 다를 수 있어요.</span></div>}
      <p className="small muted">{station.statusCollectedAt ? `${collectionTime(station.statusCollectedAt)} 마지막 수집` : '상태 수집 시각 정보 없음'}</p>
      {fast.length > 0 && <><div className="charger-gauge" role="meter" aria-label="급속충전기 이용 가능 비율" aria-valuemin={0} aria-valuemax={fast.length} aria-valuenow={available} aria-valuetext={`이용 가능 ${available}대, 전체 급속충전기 ${fast.length}대${suspect ? `, 상태 의심 ${suspect}대` : ''}${unknown ? `, 상태 미확인 ${unknown}대` : ''}`}>
        {segments.filter(segment => segment.count > 0).map(segment => <span key={segment.key} className={`charger-gauge__segment charger-gauge__segment--${segment.key}`} style={{ width: `${segment.count / fast.length * 100}%` }} aria-hidden="true" />)}
      </div><div className="charger-gauge-legend">{segments.filter(segment => segment.count > 0).map(segment => <span key={segment.key}><i className={`charger-gauge-legend__dot charger-gauge__segment--${segment.key}`} aria-hidden="true" />{segment.label} {segment.count}</span>)}</div></>}
      {fast.length === 0 ? <EmptyState title="급속충전기 정보가 없어요" /> : <div className="charger-list">{sortedFast.map(charger => <article key={charger.chargerId} className="charger-row"><div className="row between"><strong>충전기 {charger.chgerId}</strong>{charger.reliability === 'NORMAL' ? <StatusBadge status={charger.normalizedStatus} /> : <Badge tone={charger.reliability === 'SUSPECT' ? 'warning' : charger.reliability === 'FAULT' ? 'danger' : 'neutral'}>{charger.reliability === 'SUSPECT' ? '상태 의심' : charger.reliability === 'FAULT' ? '고장·점검' : '상태 미확인'}</Badge>}</div><p className="small muted">{chargerTypeLabel(charger.chargerType)} · {charger.outputKw === null ? '출력 정보 없음' : `${charger.outputKw}kW`} · 급속</p><div className="row between wrap"><span className="small muted">{detailStatusTime(charger.statusUpdatedAt)}{charger.reliability === 'SUSPECT' && ' · 상태 변경 시각이 오래되어 확인 필요'}</span><Link className="text-link" to={`/stations/${stationId}/chargers/${charger.chargerId}/history`}>상태 이력 보기</Link></div></article>)}</div>}
    </section>
    <section className="card stack"><h2>운영 정보</h2><dl className="detail-list"><div><dt>운영기관</dt><dd>{station.operatorName || station.orgName || '정보 없음'}</dd></div><div><dt>이용 시간</dt><dd>{station.useTime || '정보 없음'}</dd></div><div><dt>주차 요금</dt><dd>{station.parkingFree === null ? '정보 없음' : station.parkingFree ? '무료' : '유료'}</dd></div><div><dt>이용 제한</dt><dd>{station.limited === null ? '정보 없음' : station.limited ? station.limitDetail || '제한 있음' : '없음'}</dd></div></dl>
      {station.operatorCall && <a className="text-link row" href={`tel:${station.operatorCall}`}><Phone size={16} /> 운영기관 문의 {station.operatorCall}</a>}
    </section>
    <div className="detail-links"><Link className="button button--secondary" to={`/stations/${stationId}/prediction`}>도착 시점 예측</Link><Link className="button button--secondary" to={`/stations/${stationId}/patterns`}>혼잡 패턴</Link></div>
    <p className="search-footnote">이용 가능 대수는 정상으로 분류된 급속충전기 기준입니다. 상태 변경 시각과 마지막 수집 시각은 다르며, 도착 시점의 이용 가능성과 다를 수 있습니다.</p>
  </>;
}
