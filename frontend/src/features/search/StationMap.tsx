import { useEffect, useRef, useState } from 'react';
import { MapPin } from 'lucide-react';
import { Button } from '../../components/ui';
import { config } from '../../config/env';
import { loadKakaoMaps } from './kakaoSdk';
import type { Bounds, Coordinates, StationSummary } from './contracts';
import { statusTone } from './searchUtils';

interface Props {
  stations: StationSummary[]; center: Coordinates; position: Coordinates | null; selectedId: number | null;
  onSelect: (id: number) => void; onBoundsChange: (bounds: Bounds) => void; onAvailabilityChange: (available: boolean) => void;
}

export function StationMap({ stations, center, position, selectedId, onSelect, onBoundsChange, onAvailabilityChange }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const initialCenter = useRef(center);
  const [map, setMap] = useState<kakao.maps.Map | null>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    let observer: ResizeObserver | undefined;
    let removeIdle: (() => void) | undefined;
    loadKakaoMaps().then(maps => {
      if (!active || !container.current) return;
      const instance = new maps.Map(container.current, { center: new maps.LatLng(initialCenter.current.lat, initialCenter.current.lng), level: 8 });
      instance.addControl(new maps.ZoomControl(), maps.ControlPosition.TOPRIGHT);
      const idle = () => {
        const bounds = instance.getBounds();
        onBoundsChange({ minLat: bounds.getSouthWest().getLat(), minLng: bounds.getSouthWest().getLng(), maxLat: bounds.getNorthEast().getLat(), maxLng: bounds.getNorthEast().getLng() });
      };
      maps.event.addListener(instance, 'idle', idle);
      removeIdle = () => maps.event.removeListener(instance, 'idle', idle);
      observer = new ResizeObserver(() => { instance.relayout(); idle(); });
      observer.observe(container.current);
      setMap(instance); setState('ready'); onAvailabilityChange(true); idle();
    }).catch(() => { if (active) { setState('error'); onAvailabilityChange(false); } });
    return () => { active = false; observer?.disconnect(); removeIdle?.(); };
  }, [revision, onBoundsChange, onAvailabilityChange]);

  useEffect(() => { map?.setCenter(new kakao.maps.LatLng(center.lat, center.lng)); }, [map, center.lat, center.lng]);

  useEffect(() => {
    if (!map) return;
    let overlays: kakao.maps.CustomOverlay[] = [];
    const draw = () => {
      overlays.forEach(overlay => overlay.setMap(null)); overlays = [];
      const groups = new Map<string, StationSummary[]>();
      const projection = map.getProjection();
      for (const station of stations) {
        const pixel = projection.containerPointFromCoords(new kakao.maps.LatLng(station.lat, station.lng));
        const key = map.getLevel() <= 3 ? String(station.stationId) : `${Math.floor(pixel.x / 52)}:${Math.floor(pixel.y / 52)}`;
        groups.set(key, [...(groups.get(key) || []), station]);
      }
      for (const group of groups.values()) {
        const station = group[0]; const cluster = group.length > 1;
        const button = document.createElement('button'); button.type = 'button';
        button.className = `station-marker ${cluster ? 'station-marker--cluster' : `station-marker--${statusTone(station)}`} ${group.some(s => s.stationId === selectedId) ? 'station-marker--selected' : ''}`;
        button.textContent = cluster ? String(group.length) : statusTone(station) === 'neutral' ? '?' : String(station.availableCount);
        button.setAttribute('aria-label', cluster ? `충전소 ${group.length}곳 확대` : `${station.name} 선택`);
        button.onclick = () => {
          if (cluster) { const bounds = new kakao.maps.LatLngBounds(); group.forEach(s => bounds.extend(new kakao.maps.LatLng(s.lat, s.lng))); map.setBounds(bounds, 40); }
          else onSelect(station.stationId);
        };
        const coords = new kakao.maps.LatLng(group.reduce((v, s) => v + s.lat, 0) / group.length, group.reduce((v, s) => v + s.lng, 0) / group.length);
        overlays.push(new kakao.maps.CustomOverlay({ map, position: coords, content: button, yAnchor: 0.5, xAnchor: 0.5 }));
      }
    };
    draw(); kakao.maps.event.addListener(map, 'idle', draw);
    return () => { kakao.maps.event.removeListener(map, 'idle', draw); overlays.forEach(overlay => overlay.setMap(null)); };
  }, [map, stations, selectedId, onSelect]);

  useEffect(() => {
    if (!map || !position) return;
    const point = document.createElement('span'); point.className = 'current-position-marker';
    point.setAttribute('role', 'img'); point.setAttribute('aria-label', '현재 위치');
    const overlay = new kakao.maps.CustomOverlay({ map, position: new kakao.maps.LatLng(position.lat, position.lng), content: point, xAnchor: 0.5, yAnchor: 0.5 });
    return () => overlay.setMap(null);
  }, [map, position]);

  return <>
    <div ref={container} className="station-map" aria-label="카카오맵" />
    {state !== 'ready' && <div className="map-placeholder" role="status"><MapPin size={32} aria-hidden="true" /><h2>{state === 'loading' ? '지도를 불러오고 있어요' : '카카오맵을 준비하고 있어요'}</h2><p>{config.kakaoMapAppKey ? '지도를 불러오지 못하면 아래 목록으로 충전소를 확인해 주세요.' : '지도 연결 후 충전소 위치가 표시됩니다. 지금은 아래 목록으로 UI를 확인할 수 있어요.'}</p>{state === 'error' && config.kakaoMapAppKey && <Button variant="secondary" onClick={() => { setState('loading'); setRevision(value => value + 1); }}>다시 시도</Button>}</div>}
  </>;
}
