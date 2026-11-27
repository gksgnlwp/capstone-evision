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

export function DestinationSearch({ initialValue, onSelect }: {
  initialValue: string;
  onSelect: (destination: Destination) => void;
}) {
  const [text, setText] = useState(initialValue);
  const [search, setSearch] = useState<{ keyword: string } | null>(null);
  return <div className="stack">
    <form className="field" onSubmit={event => {
      event.preventDefault();
      if (text.trim()) setSearch({ keyword: text.trim() });
    }}>
      <label htmlFor="recommend-destination">목적지</label>
      <div className="recommend-destination-input">
        <input id="recommend-destination" value={text} maxLength={100} placeholder="장소명으로 검색"
          onChange={event => { setText(event.target.value); setSearch(null); }} />
        <Button type="submit" variant="secondary" disabled={!text.trim() || !config.kakaoMapAppKey}>검색</Button>
      </div>
    </form>
    {!config.kakaoMapAppKey && <p className="small muted">카카오맵 키가 설정되면 목적지를 검색할 수 있어요.</p>}
    {search && <PlaceResults search={search} onSelect={onSelect} />}
    {config.environment === 'DEV' && <Button type="button" variant="ghost" onClick={() => onSelect(demoDestination)}>시연 목적지 사용</Button>}
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
  return <ul className="recommend-place-results" aria-label="목적지 검색 결과">
    {places.map(({ place, destination }) => <li key={place.id}>
      <button type="button" onClick={() => onSelect(destination)}>
        <strong>{destination.name}</strong>
        <span className="small muted">{place.road_address_name || place.address_name}</span>
      </button>
    </li>)}
  </ul>;
}
