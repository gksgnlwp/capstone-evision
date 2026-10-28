import { useCallback, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { LocateFixed, Search } from 'lucide-react';
import { Button, EmptyState, ErrorState, LoadingState } from '../../components/ui';
import { useAsync } from '../../hooks/useAsync';
import { useLocationAccess } from './LocationContext';
import { SearchFilters, type AccessFilter } from './SearchFilters';
import { searchService } from './searchService';
import { StationCard } from './StationCard';
import { StationMap } from './StationMap';
import { sortStations } from './searchUtils';
import type { Bounds, Coordinates } from './contracts';
import { config } from '../../config/env';

const defaultCenter: Coordinates = { lat: 37.25, lng: 127.09 };
function sameBounds(a: Bounds | null, b: Bounds | null) {
  return a && b && Object.keys(a).every(key => Math.abs(a[key as keyof Bounds] - b[key as keyof Bounds]) < 0.00001);
}

export function MapSearchPage() {
  const location = useLocationAccess();
  const navigate = useNavigate();
  const [center, setCenter] = useState<Coordinates>(() => location.position || defaultCenter);
  const [bounds, setBounds] = useState<Bounds | null>(() => config.environment === 'DEV' ? { minLat: 33, minLng: 124, maxLat: 39, maxLng: 132 } : null);
  const [mapAvailable, setMapAvailable] = useState(false);
  const [viewport, setViewport] = useState<Bounds | null>(null);
  const [accessType, setAccessType] = useState<AccessFilter>('');
  const [chargerType, setChargerType] = useState('');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [visibleCount, setVisibleCount] = useState(10);
  const loader = useCallback(async (signal: AbortSignal) => bounds ? searchService.search({ bounds, accessType: accessType || undefined, chargerType: chargerType || undefined, limit: 500 }, signal) : null, [bounds, accessType, chargerType]);
  const result = useAsync(loader);
  const stations = result.data?.stations || [];
  const selected = stations.find(s => s.stationId === selectedId);
  const changed = !sameBounds(bounds, viewport);
  const onBoundsChange = useCallback((next: Bounds) => {
    setViewport(next);
    setBounds(current => current || next);
  }, []);
  const onAvailabilityChange = useCallback((available: boolean) => {
    setMapAvailable(available);
    if (available) setBounds(null);
  }, []);
  async function locate() {
    const position = await location.request();
    if (position) { setCenter(position); setSelectedId(null); }
    else navigate('/location');
  }

  return <div className="stack map-search-page">
    <div className="row between"><h1>내 주변 지도</h1><Button variant="ghost" className="compact-button" onClick={locate} loading={location.status === 'loading'} aria-label="현재 위치 확인"><LocateFixed size={20} /></Button></div>
    {!location.position && <p className="small muted">위치 미확인 · 지도를 이동해 직접 찾아보세요.</p>}
    <SearchFilters accessType={accessType} chargerType={chargerType} onAccessChange={value => { setAccessType(value); setVisibleCount(10); setSelectedId(null); }} onChargerChange={value => { setChargerType(value); setVisibleCount(10); setSelectedId(null); }} />
    <div className="map-frame" role="region" aria-label="충전소 지도">
      <StationMap stations={stations} center={center} position={location.position} selectedId={selectedId} onSelect={setSelectedId} onBoundsChange={onBoundsChange} onAvailabilityChange={onAvailabilityChange} />
      {mapAvailable && <Button variant="secondary" className="map-search-button" disabled={!viewport || !changed} onClick={() => { setBounds(viewport); setSelectedId(null); setVisibleCount(10); }}><Search size={16} />이 영역 검색</Button>}
    </div>
    {mapAvailable && <p className="map-legend"><span className="legend-dot legend-dot--success" />이용 가능 <span className="legend-dot legend-dot--danger" />가능 대수 0 <span className="legend-dot legend-dot--neutral" />미확인 <span className="legend-dot legend-dot--cluster" />충전소 묶음</p>}
    {selected && result.data && <StationCard station={selected} origin={location.position} receivedAt={result.data.receivedAt} selected />}
    {result.status === 'loading' && <LoadingState />}
    {result.status === 'error' && <ErrorState message={result.error.message} onRetry={result.retry} />}
    {result.status === 'success' && result.data && <>
      <h2>{mapAvailable ? '이 영역의 충전소' : '충전소 목록'} <span className="result-count">{stations.length}</span></h2>
      {stations.length === 0 ? <EmptyState title="이 영역에 충전소가 없어요" description="지도를 이동하거나 필터를 바꿔 보세요." /> : <div className="station-list">{sortStations(stations, location.position).slice(0, visibleCount).map(station => <StationCard key={station.stationId} station={station} origin={location.position} receivedAt={result.data!.receivedAt} />)}</div>}
      {stations.length > visibleCount && <Button variant="secondary" onClick={() => setVisibleCount(value => value + 10)}>충전소 더 보기</Button>}
      {result.data.truncated && <p className="small muted">최대 500곳까지만 표시합니다. 지도를 확대하거나 필터로 범위를 좁혀 주세요.</p>}
    </>}
    <p className="search-footnote">{mapAvailable ? '개별 숫자는 급속충전기 전체 기준 이용 가능 대수입니다. 묶음 마커는 충전소 수이며, 누르면 확대됩니다.' : '카카오맵 연결 전에는 목록에서 충전소 화면 구성을 확인할 수 있습니다.'}</p>
    <Link to="/route" className="text-link">노선·방향으로 찾기</Link>
  </div>;
}
