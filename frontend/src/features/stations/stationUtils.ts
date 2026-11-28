import { statusTime } from '../search/searchUtils';
import type { StationDetail, StationPattern } from './contracts';

export function detailAccessLabel(access: StationDetail['accesses'][number]) {
  return access.accessType === 'REST_AREA'
    ? `${access.restAreaName || '휴게소'}${access.direction ? ` · ${access.direction}` : ''}`
    : `${access.icName || 'IC'}${access.detourKm === null ? '' : ` · 우회 약 ${access.detourKm.toFixed(1)}km`}`;
}
export function chargerTypeLabel(code: string | null) {
  return ({ '04': 'DC콤보', '01': 'DC차데모', '03': '차데모·AC3상', '05': '차데모·콤보', '06': '차데모·AC3상·콤보', '07': 'AC3상', '09': 'NACS', '10': 'DC콤보·NACS' } as Record<string, string>)[code || ''] || code || '유형 정보 없음';
}
export function detailStatusTime(value: string | null) { return value ? `${statusTime(value)} 갱신` : '갱신 정보 없음'; }
export function localKst(date: Date) { return new Date(date.getTime() + 9 * 3600000).toISOString().slice(0, 19); }

export function groupPatternSlots(slots: StationPattern['slots']) {
  const days = Array.from({ length: 7 }, () => Array.from({ length: 8 }, () => ({
    samples: 0, occupancySum: 0, comfortSum: 0,
  })));
  for (const slot of slots) {
    if (slot.averageOccupancyRate === null || slot.comfortRate === null) continue;
    const period = Math.floor(Number(slot.time.slice(0, 2)) / 3);
    const cell = days[slot.dayOfWeek - 1][period];
    cell.samples += slot.samples;
    cell.occupancySum += slot.averageOccupancyRate * slot.samples;
    cell.comfortSum += slot.comfortRate * slot.samples;
  }
  return days.map(day => day.map(({ samples, occupancySum, comfortSum }) => ({
    samples, occupancy: samples ? occupancySum / samples : null, comfort: samples ? comfortSum / samples : null,
  })));
}
