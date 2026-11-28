import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ChevronRight, MapPin, Navigation } from 'lucide-react';
import { Button, Chip } from '../components/ui';
import { readRecentDestinations, saveRecentDestination } from '../features/search/recentDestinations';
import { OriginPicker } from '../features/recommendations/OriginPicker';
import { originLabel } from '../features/recommendations/navigation';
import { useLocationAccess } from '../features/search/LocationContext';

/** M01: 별도 소개나 화면 목록을 거치지 않고 목적지 설정에서 시작합니다. */
export function HomePage() {
  const navigate = useNavigate();
  const [destination, setDestination] = useState('');
  const [recent] = useState(readRecentDestinations);
  const location = useLocationAccess();
  const [originOpen, setOriginOpen] = useState(false);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!destination.trim()) return;
    saveRecentDestination(destination);
    navigate(`/recommendations?${new URLSearchParams({ destination: destination.trim() })}`);
  }

  return <div className="home-page">
    <div className="stack">
      <div className="home-intro"><p>고속도로 충전 계획</p><h1>어디까지 가세요?</h1><span>가는 길의 충전소를 찾아보세요.</span></div>
      <form className="stack" onSubmit={submit}>
        <div className="journey-fields">
          <div className="journey-field">
            <Navigation size={18} className="journey-origin-icon" aria-hidden="true" />
            <div className="journey-field__body"><span className="journey-label">출발</span><span>{originLabel(location.position, location.label)}</span></div>
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
        <OriginPicker open={originOpen} onOpenChange={setOriginOpen} />
        {recent.length > 0 && <div className="recent-destinations"><p className="journey-label">최근 목적지</p><div className="row wrap">{recent.map(item => <Chip key={item} selected={destination === item} onClick={() => setDestination(item)}>{item}</Chip>)}</div></div>}
        <Button type="submit" fullWidth disabled={!destination.trim()}>충전소 추천 받기</Button>
        <Link to="/map" className="home-map-link">내 주변 지도로 보기 <ChevronRight size={18} aria-hidden="true" /></Link>
      </form>
    </div>
    <p className="home-guidance">목적지로 지나갈 고속도로 노선과 방향을 자동으로 정합니다. 실제 길안내 경로가 아닙니다.</p>
  </div>;
}
