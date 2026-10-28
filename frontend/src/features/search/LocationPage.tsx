import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { MapPin, ShieldCheck } from 'lucide-react';
import { Button } from '../../components/ui';
import { useLocationAccess } from './LocationContext';

export function LocationPage() {
  const location = useLocationAccess();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const retry = location.status === 'denied' || location.status === 'error';
  async function request() {
    if (await location.request()) navigate(params.get('returnTo') === 'home' ? '/' : '/map?locate=1');
  }
  return <div className="location-page stack">
    <div className={`location-illustration ${retry ? 'location-illustration--warning' : ''}`}><MapPin size={40} strokeWidth={1.5} aria-hidden="true" /></div>
    <h1>{retry ? '현재 위치를 확인할 수 없어요' : location.position ? '현재 위치를 확인했어요' : '내 주변 충전소를 찾아볼까요?'}</h1>
    <p className="muted small">{retry ? location.message : '위치를 확인하면 가까운 휴게소와 IC 인근 충전소를 찾을 수 있어요. 위치를 허용하지 않아도 목적지나 지도에서 찾을 수 있습니다.'}</p>
    <div aria-live="polite" className="sr-only">{location.status === 'loading' ? '현재 위치를 확인하고 있습니다.' : location.message}</div>
    <div className="location-actions stack">
      <Link to="/" className="button button--primary button--full">목적지로 경로 찾기</Link>
      <Link to="/map" className="button button--secondary button--full">지도에서 직접 찾기</Link>
      <Button variant="ghost" loading={location.status === 'loading'} onClick={request}>{retry ? '위치 권한 다시 요청' : '현재 위치 확인'}</Button>
    </div>
    <p className="privacy-note"><ShieldCheck size={18} aria-hidden="true" />위치는 현재 탐색 중에만 사용하며 브라우저에 저장하지 않습니다.</p>
  </div>;
}
