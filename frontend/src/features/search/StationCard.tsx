import { Link } from 'react-router-dom';
import { ChevronRight } from 'lucide-react';
import { Badge } from '../../components/ui';
import type { Coordinates, StationSummary } from './contracts';
import { availabilityLabel, distanceKm, isCollectionDelayed, statusTime, statusTone } from './searchUtils';
import { usePreferences } from '../personalization/preferences';

export function StationCard({ station, origin, receivedAt, statusCollectedAt, selected = false }: { station: StationSummary; origin: Coordinates | null; receivedAt: number; statusCollectedAt: string | null; selected?: boolean }) {
  const { operatorIds } = usePreferences();
  const preferred = station.busiId !== null && operatorIds.includes(station.busiId);
  const rest = station.accesses.find(a => a.accessType === 'REST_AREA');
  const ics = station.accesses.filter(a => a.accessType === 'IC');
  const detours = ics.map(a => a.detourKm).filter((n): n is number => n !== null);
  const delayed = isCollectionDelayed(statusCollectedAt, receivedAt);
  const tone = statusTone(station);
  return <article className={`station-card ${selected ? 'station-card--selected' : ''} ${preferred ? 'station-card--preferred' : ''}`}>
    <Link to={`/stations/${station.stationId}`} className="station-card__link" aria-label={`${station.name} 상세 보기`}>
      <div className="row between"><h2>{station.name}</h2><ChevronRight size={16} aria-hidden="true" /></div>
      <div className="row wrap station-card__tags"><Badge tone={rest ? 'success' : 'info'}>{rest ? '휴게소' : 'IC'}</Badge>{preferred && <Badge tone="success">선호 운영기관</Badge>}{rest?.direction && <span className="muted">{rest.direction}</span>}
        {ics.length > 1 && <span className="muted">IC {ics.length}곳 연결 · 1건 표시</span>}</div>
      <div className="row between wrap station-card__status">
        <span className="muted">{statusCollectedAt ? `${statusTime(statusCollectedAt)} 수집` : '수집 정보 없음'}</span>
        <Badge tone={tone}>{availabilityLabel(station)}</Badge>
      </div>
      {(origin || detours.length > 0 || delayed || station.reliability !== 'NORMAL') && <div className="station-card__meta">
        {origin && <span>직선 {distanceKm(origin, station).toFixed(1)}km</span>}
        {detours.length > 0 && <span>IC 우회거리 약 {Math.min(...detours).toFixed(1)}km</span>}
        {station.suspectCount > 0 && <Badge tone="warning">상태 의심 {station.suspectCount}대</Badge>}
        {station.unavailableCount > 0 && <Badge tone="danger">이용 불가 {station.unavailableCount}대</Badge>}
        {station.unknownCount > 0 && <Badge tone="neutral">미확인 {station.unknownCount}대</Badge>}
        {station.reliability === 'ALL_ERROR' && <Badge tone="warning">상태 확인 필요</Badge>}
        {delayed && <Badge tone="warning">상태 수집 지연</Badge>}
      </div>}
    </Link>
  </article>;
}
