import { useCallback, useState } from 'react';
import { Button, ErrorState, LoadingState } from '../../components/ui';
import { config } from '../../config/env';
import { useAsync } from '../../hooks/useAsync';
import { loadKakaoMaps } from '../search/kakaoSdk';
import { validCoordinates } from './contracts';
import { demoDestination, type Destination } from './queryState';

async function searchPlaces(keyword: string, signal: AbortSignal) {
  const { services } = await loadKakaoMaps();
  signal.throwIfAborted();
  return new Promise<kakao.maps.services.PlacesSearchResult>((resolve, reject) => {
    const places = new services.Places();
    const error = new Error('장소를 검색하지 못했습니다. 다시 시도해 주세요.');
    const timeout = window.setTimeout(() => reject(error), 10000);
    places.keywordSearch(keyword, (results, status) => {
      clearTimeout(timeout);
      if (status === services.Status.OK) resolve(results);
      else if (status === services.Status.ZERO_RESULT) resolve([]);
      else reject(error);
    }, { size: 5 });
  });
}

/**
 * 카카오 장소 검색. 목적지와 출발지 입력에 같이 씁니다.
 * 다른 form 안에서도 쓸 수 있게 form 대신 Enter 키와 버튼으로 검색합니다.
 */
export function DestinationSearch({ initialValue, onSelect, label = '목적지', inputId = 'recommend-destination',
  placeholder = '장소명으로 검색', showDemo = true }: {
  initialValue: string;
  onSelect: (destination: Destination) => void;
  label?: string;
  inputId?: string;
  placeholder?: string;
  showDemo?: boolean;
}) {
  const [text, setText] = useState(initialValue);
  // 홈에서 입력한 목적지로 들어오면 검색 버튼을 다시 누르지 않아도 바로 검색합니다.
  const [search, setSearch] = useState<{ keyword: string } | null>(() =>
    initialValue.trim() && config.kakaoMapAppKey ? { keyword: initialValue.trim() } : null);
  const submit = () => { if (text.trim()) setSearch({ keyword: text.trim() }); };
  return <div className="stack">
    <div className="field">
      <label htmlFor={inputId}>{label}</label>
      <div className="recommend-destination-input">
        <input id={inputId} value={text} maxLength={100} placeholder={placeholder} enterKeyHint="search"
          onChange={event => { setText(event.target.value); setSearch(null); }}
          onKeyDown={event => { if (event.key === 'Enter' && !event.nativeEvent.isComposing) { event.preventDefault(); submit(); } }} />
        <Button type="button" variant="secondary" onClick={submit} disabled={!text.trim() || !config.kakaoMapAppKey}>검색</Button>
      </div>
    </div>
    {!config.kakaoMapAppKey && <p className="small muted">카카오맵 키가 설정되면 장소를 검색할 수 있어요.</p>}
    {search && <PlaceResults search={search} onSelect={onSelect} />}
    {showDemo && config.environment === 'DEV' && <Button type="button" variant="ghost" onClick={() => onSelect(demoDestination)}>시연 목적지 사용</Button>}
  </div>;
}

function PlaceResults({ search, onSelect }: {
  search: { keyword: string };
  onSelect: (destination: Destination) => void;
}) {
  const loader = useCallback((signal: AbortSignal) => searchPlaces(search.keyword, signal), [search]);
  const results = useAsync(loader);
  if (results.status === 'loading') return <LoadingState label="장소를 검색하고 있습니다." />;
  if (results.status === 'error') return <ErrorState message={results.error.message} onRetry={results.retry} />;
  const places = results.data.flatMap(place => {
    if (!place.y?.trim() || !place.x?.trim() || !place.place_name?.trim()) return [];
    const destination = { name: place.place_name, lat: Number(place.y), lng: Number(place.x) };
    return validCoordinates(destination) ? [{ place, destination }] : [];
  });
  if (!places.length) return <p className="small muted" role="status">검색 결과가 없어요. 다른 장소명으로 검색해 주세요.</p>;
  return <ul className="recommend-place-results" aria-label="장소 검색 결과">
    {places.map(({ place, destination }) => <li key={place.id}>
      <button type="button" onClick={() => onSelect(destination)}>
        <strong>{destination.name}</strong>
        <span className="small muted">{place.road_address_name || place.address_name}</span>
      </button>
    </li>)}
  </ul>;
}
