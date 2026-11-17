import type { Coordinates, StationSummary } from './contracts';

export function deduplicateStations(stations: StationSummary[]) {
  const unique = new Map<number, StationSummary>();
  for (const station of stations) {
    const existing = unique.get(station.stationId);
    if (!existing) { unique.set(station.stationId, { ...station }); continue; }
    const accesses = [...existing.accesses, ...station.accesses];
    unique.set(station.stationId, { ...existing, accesses: accesses.filter((a, i) =>
      accesses.findIndex(b => b.accessType === a.accessType && b.restAreaId === a.restAreaId && b.icId === a.icId) === i) });
  }
  return [...unique.values()];
}

/** 경로/운전 거리가 아닌 좌표 간 직선 거리입니다. ETA로 변환하지 않습니다. */
export function distanceKm(from: Coordinates, to: Coordinates) {
  const rad = (n: number) => n * Math.PI / 180;
  const a = Math.sin(rad(to.lat - from.lat) / 2) ** 2 + Math.cos(rad(from.lat)) * Math.cos(rad(to.lat)) * Math.sin(rad(to.lng - from.lng) / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
}
export function sortStations(stations: StationSummary[], origin: Coordinates | null) {
  if (!origin) return stations;
  const priority = { NORMAL: 0, PARTIAL: 1, ALL_ERROR: 2 };
  return [...stations].sort((a, b) => priority[a.reliability] - priority[b.reliability]
    || distanceKm(origin, a) - distanceKm(origin, b));
}
export function statusTone(station: StationSummary) {
  if (station.reliability === 'ALL_ERROR' && station.unknownCount === station.fastChargerCount) return 'neutral' as const;
  if (station.reliability === 'ALL_ERROR' && station.suspectCount > 0) return 'warning' as const;
  if (station.reliability === 'ALL_ERROR') return 'danger' as const;
  if (station.reliability === 'PARTIAL') return 'warning' as const;
  if (station.availableCount > 0) return 'success' as const;
  return 'danger' as const;
}
export function availabilityLabel(station: StationSummary) {
  if (station.reliability === 'ALL_ERROR') return station.unknownCount === station.fastChargerCount ? '상태 미확인' : '정상 충전기 없음';
  return `이용 가능 ${station.availableCount}/${station.fastChargerCount}`;
}
export function parseKst(value: string | null): Date | null {
  if (!value) return null;
  const date = new Date(/[zZ]$|[+-]\d{2}:?\d{2}$/.test(value) ? value : value + '+09:00');
  return Number.isNaN(date.getTime()) ? null : date;
}
export function statusTime(value: string | null) {
  const date = parseKst(value);
  return date ? new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul' }).format(date) : '갱신 정보 없음';
}
export function isCollectionDelayed(value: string | null, now = Date.now()) {
  const collected = parseKst(value);
  return collected !== null && now - collected.getTime() >= 10 * 60 * 1000;
}

export const chargerOptions = [
  { value: '', label: '충전기 전체' },
  { value: '04', label: 'DC콤보' },
  { value: '01', label: 'DC차데모' },
  { value: '03', label: '차데모·AC3상' },
  { value: '05', label: '차데모·콤보' },
  { value: '06', label: '차데모·AC3상·콤보' },
  { value: '07', label: 'AC3상' },
  { value: '09', label: 'NACS' },
  { value: '10', label: 'DC콤보·NACS' },
];
// 백엔드는 복합형 충전기도 포함해 커넥터 타입을 검색합니다.
