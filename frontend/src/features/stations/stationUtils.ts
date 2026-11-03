import { parseKst, statusTime } from '../search/searchUtils';
import type { StationDetail } from './contracts';

export function detailAccessLabel(access: StationDetail['accesses'][number]) {
  return access.accessType === 'REST_AREA'
    ? `${access.restAreaName || '휴게소'}${access.direction ? ` · ${access.direction}` : ''}`
    : `${access.icName || 'IC'}${access.detourKm === null ? '' : ` · 우회 약 ${access.detourKm.toFixed(1)}km`}`;
}
export function chargerTypeLabel(code: string | null) {
  return ({ '04': 'DC콤보', '01': 'DC차데모', '03': '차데모·AC3상', '05': '차데모·콤보', '06': '차데모·AC3상·콤보', '07': 'AC3상' } as Record<string, string>)[code || ''] || code || '유형 정보 없음';
}
export function detailStatusTime(value: string | null) { return value ? `${statusTime(value)} 갱신` : '갱신 정보 없음'; }
export function isStatusDelayed(value: string | null, now = Date.now()) {
  const updated = parseKst(value);
  return updated !== null && now - updated.getTime() >= 10 * 60 * 1000;
}
export function localKst(date: Date) { return new Date(date.getTime() + 9 * 3600000).toISOString().slice(0, 19); }
