import type { Coordinates } from '../search/contracts';

type Place = Coordinates & { name: string };

/** 카카오맵 링크에서 구분자로 쓰는 쉼표·슬래시는 이름에서 뺍니다. */
function placeSegment({ name, lat, lng }: Place) {
  const safe = name.replace(/[,/]/g, ' ').replace(/\s+/g, ' ').trim() || '목적지';
  return `${encodeURIComponent(safe)},${lat},${lng}`;
}

/**
 * 카카오맵 자동차 길찾기 링크. 출발지가 있으면 출발지 → 충전소, 없으면 카카오맵이 현재 위치에서 안내합니다.
 * https://map.kakao.com/link/from/이름,위도,경도/to/이름,위도,경도
 */
export function kakaoRouteUrl(to: Place, from?: Place | null) {
  return from
    ? `https://map.kakao.com/link/from/${placeSegment(from)}/to/${placeSegment(to)}`
    : `https://map.kakao.com/link/to/${placeSegment(to)}`;
}

/** 출발지 표시 문구. 직접 입력했으면 장소 이름, 아니면 현재 위치 확인 여부 */
export function originLabel(position: Coordinates | null, label: string | null) {
  if (label) return label;
  return position ? '현재 위치' : '출발지 미설정';
}
