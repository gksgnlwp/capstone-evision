import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ChevronRight, MapPin, Navigation } from 'lucide-react';
import { Button, Chip } from '../components/ui';
import { readRecentDestinations, saveRecentDestination } from '../features/search/recentDestinations';
import { useLocationAccess } from '../features/search/LocationContext';

/** M01: 별도 소개나 화면 목록을 거치지 않고 목적지 설정에서 시작합니다. */
export function HomePage() {
  const navigate = useNavigate();
  const [destination, setDestination] = useState('');
  const [recent] = useState(readRecentDestinations);
  const location = useLocationAccess();

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!destination.trim()) return;
    saveRecentDestination(destination);
    navigate(`/route?${new URLSearchParams({ destination: destination.trim() })}`);
  }

  return <div className="home-page">
    <div className="stack">
      <h1>어디까지 가세요?</h1>
      <form className="stack" onSubmit={submit}>
        <div className="journey-fields">
          <div className="journey-field">
            <Navigation size={18} className="journey-origin-icon" aria-hidden="true" />
            <div className="journey-field__body"><span className="journey-label">출발</span><span>{location.position ? '현재 위치 확인됨' : '현재 위치 미확인'}</span></div>
            <Link to="/location?returnTo=home" className="text-link">위치 확인</Link>
          </div>
          <div className="journey-field">
            <MapPin size={18} className="journey-destination-icon" aria-hidden="true" />
            <div className="journey-field__body">
              <label htmlFor="destination" className="journey-label">목적지</label>
              <input id="destination" name="destination" placeholder="어디로 가시나요?" value={destination}
                onChange={event => setDestination(event.target.value)} required maxLength={100} autoComplete="off" />
            </div>
          </div>
        </div>
        {recent.length > 0 && <div className="recent-destinations"><p className="journey-label">최근 목적지</p><div className="row wrap">{recent.map(item => <Chip key={item} selected={destination === item} onClick={() => setDestination(item)}>{item}</Chip>)}</div></div>}
        <Link to="/route" className="route-shortcut">
          <div><p className="small muted">이용할 고속도로를 알고 있다면</p><span>노선·방향 직접 선택하기</span></div>
          <ChevronRight size={20} aria-hidden="true" />
        </Link>
        <Button type="submit" fullWidth disabled={!destination.trim()}>경로 위 충전소 보기</Button>
        <Link to="/map" className="button button--secondary button--full">내 주변 지도로 보기</Link>
      </form>
    </div>
    <p className="home-guidance">목적지는 지나갈 고속도로 노선·방향을 정하는 데 사용합니다. 실제 길안내 경로가 아닙니다.</p>
  </div>;
}
