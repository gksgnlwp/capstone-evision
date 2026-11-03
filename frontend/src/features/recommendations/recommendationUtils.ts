import type { RecommendationItem } from './contracts';

export function probabilityLabel(value: number) { return `${Math.round(value * 100)}%`; }
export function basisLabel(item: RecommendationItem) {
  if (item.basis === 'AI_FORECAST') return 'AI 예측 기반';
  if (item.basis === 'PRIOR') return '기본 통계 기반';
  return '과거 이용 통계 기반';
}
export function accessLabel(item: RecommendationItem) {
  return item.access.accessType === 'REST_AREA'
    ? `휴게소${item.access.direction ? ` · ${item.access.direction}` : ''}`
    : `IC${item.access.icName ? ` · ${item.access.icName}` : ''}`;
}
export function reasonLabel(item: RecommendationItem) {
  const access = item.access.accessType === 'IC'
    ? `IC 인근 · 우회거리 약 ${item.detourKm.toFixed(1)}km`
    : '휴게소 충전소';
  return `${access} · 이용 가능 확률 ${probabilityLabel(item.availabilityProbability)} · 급속충전기 ${item.fastChargerCount}대`;
}
export function arrivalLabel(eta: string) {
  const parsed = new Date(/(?:Z|[+-]\d\d:\d\d)$/.test(eta) ? eta : `${eta}+09:00`);
  return Number.isNaN(parsed.getTime()) ? '정보 없음' : new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hour12: false }).format(parsed);
}
export function externalDirectionsUrl(item: RecommendationItem) {
  return `https://map.kakao.com/link/to/${encodeURIComponent(item.name)},${item.latitude},${item.longitude}`;
}
