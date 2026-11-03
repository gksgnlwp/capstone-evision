import { useEffect, useRef, useState } from 'react';
import { MapPin } from 'lucide-react';
import { config } from '../../config/env';
import { loadKakaoMaps } from '../search/kakaoSdk';
import type { RecommendationItem } from './contracts';

export function RecommendationMap({ items }: { items: RecommendationItem[] }) {
  const container = useRef<HTMLDivElement>(null);
  const [available, setAvailable] = useState(false);
  useEffect(() => {
    let active = true;
    let overlays: kakao.maps.CustomOverlay[] = [];
    let observer: ResizeObserver | undefined;
    loadKakaoMaps().then(maps => {
      if (!active || !container.current) return;
      const first = items[0];
      const map = new maps.Map(container.current, { center: new maps.LatLng(first.latitude, first.longitude), level: 8 });
      const bounds = new maps.LatLngBounds();
      for (const item of items) {
        const position = new maps.LatLng(item.latitude, item.longitude);
        bounds.extend(position);
        const label = document.createElement('span');
        label.className = `recommend-map-marker ${item.access.accessType === 'IC' ? 'recommend-map-marker--ic' : ''}`;
        label.textContent = `${item.rank} ${item.access.restAreaName || item.access.icName || item.name}`;
        overlays.push(new maps.CustomOverlay({ map, position, content: label, yAnchor: 0.5, xAnchor: 0.5 }));
      }
      map.setBounds(bounds, 32);
      observer = new ResizeObserver(() => map.relayout());
      observer.observe(container.current);
      setAvailable(true);
    }).catch(() => { if (active) setAvailable(false); });
    return () => { active = false; observer?.disconnect(); overlays.forEach(overlay => overlay.setMap(null)); overlays = []; };
  }, [items]);
  return <div className="map-frame recommend-map" role="region" aria-label="비교 충전소 지도">
    <div ref={container} className="recommend-map__canvas" />
    {!available && <div className="map-placeholder"><MapPin size={25} aria-hidden="true" /><p>{config.kakaoMapAppKey ? '지도를 불러오는 중입니다.' : '카카오맵 연결 후 두 충전소 위치가 표시됩니다.'}</p></div>}
  </div>;
}
