import { LocateFixed, Search } from 'lucide-react';
import { Button } from '../../components/ui';
import { useLocationAccess } from '../search/LocationContext';
import { DestinationSearch } from './DestinationSearch';

/**
 * 출발지 선택: 현재 위치를 확인하거나 장소를 검색해 직접 정합니다.
 * 고른 출발지는 브라우저 메모리에만 둡니다 (새로고침하면 사라짐).
 */
export function OriginPicker({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const location = useLocationAccess();
  return <div className="stack origin-picker">
    <div className="origin-picker__actions">
      <Button type="button" variant="secondary" loading={location.status === 'loading'}
        onClick={() => { onOpenChange(false); void location.request(); }}><LocateFixed size={16} aria-hidden="true" /> 현재 위치</Button>
      <Button type="button" variant={open ? 'primary' : 'secondary'} aria-expanded={open}
        onClick={() => onOpenChange(!open)}><Search size={16} aria-hidden="true" /> 직접 입력</Button>
    </div>
    {open && <DestinationSearch initialValue="" label="출발지" inputId="origin-search" placeholder="출발할 장소 검색" showDemo={false}
      onSelect={place => { location.setManual({ lat: place.lat, lng: place.lng }, place.name); onOpenChange(false); }} />}
    {location.message && !location.position && <p role="alert" className="small field__error">{location.message}</p>}
  </div>;
}
