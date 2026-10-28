import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import type { Coordinates } from './contracts';

type LocationState = { status: 'idle' | 'loading' | 'success' | 'denied' | 'error'; position: Coordinates | null; message: string };
interface LocationValue extends LocationState { request: () => Promise<Coordinates | null> }
const LocationContext = createContext<LocationValue | null>(null);

export function LocationProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<LocationState>({ status: 'idle', position: null, message: '' });
  const requestId = useRef(0);
  const request = useCallback(async (): Promise<Coordinates | null> => {
    const id = ++requestId.current;
    if (!navigator.geolocation) {
      setState({ status: 'error', position: null, message: '이 브라우저에서는 위치 확인을 지원하지 않습니다.' });
      return null;
    }
    setState({ status: 'loading', position: null, message: '' });
    return new Promise(resolve => {
      navigator.geolocation.getCurrentPosition(result => {
        if (id !== requestId.current) { resolve(null); return; }
        const position = { lat: result.coords.latitude, lng: result.coords.longitude };
        setState({ status: 'success', position, message: '' }); resolve(position);
      }, error => {
        if (id !== requestId.current) { resolve(null); return; }
        const message = error.code === 1 ? '위치 권한이 꺼져 있습니다. 브라우저의 사이트 설정에서 권한을 허용해 주세요.'
          : error.code === 3 ? '위치 확인 시간이 초과되었습니다. 다시 시도해 주세요.' : '위치를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.';
        setState({ status: error.code === 1 ? 'denied' : 'error', position: null, message }); resolve(null);
      }, { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 });
    });
  }, []);
  // 위치는 브라우저 메모리에서만 유지합니다. 새로고침하면 폐기됩니다.
  return <LocationContext value={{ ...state, request }}>{children}</LocationContext>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useLocationAccess() {
  const value = useContext(LocationContext);
  if (!value) throw new Error('LocationProvider가 필요합니다.');
  return value;
}
