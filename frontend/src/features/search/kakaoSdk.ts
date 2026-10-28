import { config } from '../../config/env';

let sdkPromise: Promise<typeof kakao.maps> | null = null;

export function loadKakaoMaps(): Promise<typeof kakao.maps> {
  if (!config.kakaoMapAppKey) return Promise.reject(new Error('카카오맵 키가 설정되지 않았습니다.'));
  if (sdkPromise) return sdkPromise;
  sdkPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.id = 'evision-kakao-maps-sdk';
    const timeout = window.setTimeout(() => fail(), 15000);
    function fail() {
      clearTimeout(timeout);
      script.remove();
      reject(new Error('카카오맵을 불러오지 못했습니다.'));
    }
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?${new URLSearchParams({ appkey: config.kakaoMapAppKey, autoload: 'false' })}`;
    script.async = true;
    script.onerror = fail;
    script.onload = () => {
      if (typeof kakao === 'undefined' || !kakao.maps?.load) { fail(); return; }
      kakao.maps.load(() => { clearTimeout(timeout); resolve(kakao.maps); });
    };
    document.head.append(script);
  });
  sdkPromise.catch(() => { sdkPromise = null; });
  return sdkPromise;
}
