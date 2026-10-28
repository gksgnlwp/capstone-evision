import { Link } from 'react-router-dom';
import { ChevronRight } from 'lucide-react';
import { Badge } from '../../components/ui';
import type { Coordinates, StationSummary } from './contracts';
import { distanceKm, parseKst, statusTime, statusTone } from './searchUtils';

export function StationCard({ station, origin, receivedAt, selected = false }: { station: StationSummary; origin: Coordinates | null; receivedAt: number; selected?: boolean }) {
  const rest = station.accesses.find(a => a.accessType === 'REST_AREA');
  const ics = station.accesses.filter(a => a.accessType === 'IC');
  const detours = ics.map(a => a.detourKm).filter((n): n is number => n !== null);
  const date = parseKst(station.latestStatusUpdatedAt);
  const delayed = date && receivedAt - date.getTime() > 10 * 60 * 1000;
  const tone = statusTone(station);
  return <article className={`station-card ${selected ? 'station-card--selected' : ''}`}>
    <Link to={`/stations/${station.stationId}`} className="station-card__link" aria-label={`${station.name} 상세 보기`}>
      <div className="row between"><h2>{station.name}</h2><ChevronRight size={16} aria-hidden="true" /></div>
      <div className="row wrap station-card__tags"><Badge tone={rest ? 'success' : 'info'}>{rest ? '휴게소' : 'IC'}</Badge>{rest?.direction && <span className="muted">{rest.direction}</span>}
        {ics.length > 1 && <span className="muted">IC {ics.length}곳 연결 · 1건 표시</span>}</div>
      <div className="row between wrap station-card__status">
        <span className="muted">{date ? `${statusTime(station.latestStatusUpdatedAt)} 갱신` : '갱신 정보 없음'}</span>
        <Badge tone={tone}>{tone === 'neutral' ? '상태 미확인' : station.unknownCount > 0 && station.availableCount === 0 ? `미확인 ${station.unknownCount}대` : `이용 가능 ${station.availableCount}/${station.fastChargerCount}`}</Badge>
      </div>
      {(origin || detours.length > 0 || delayed) && <div className="station-card__meta">
        {origin && <span>직선 {distanceKm(origin, station).toFixed(1)}km</span>}
        {detours.length > 0 && <span>IC 우회거리 약 {Math.min(...detours).toFixed(1)}km</span>}
        {delayed && <Badge tone="warning">최근 갱신 지연</Badge>}
      </div>}
    </Link>
  </article>;
}
