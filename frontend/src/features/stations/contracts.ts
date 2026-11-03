import { z } from 'zod';
import { accessSchema } from '../search/contracts';

const nullableText = z.string().nullable();
const localDate = z.string().nullable();
const status = z.enum(['AVAILABLE', 'CHARGING', 'UNAVAILABLE', 'UNKNOWN']);

export const chargerSchema = z.object({
  chargerId: z.number().int().positive(), chgerId: z.string(), chargerType: nullableText,
  outputKw: z.number().int().nonnegative().nullable(), method: nullableText, fast: z.boolean(),
  normalizedStatus: status, statusCode: nullableText, statusUpdatedAt: localDate,
});

export const stationDetailSchema = z.object({
  stationId: z.number().int().positive(), statId: z.string(), name: z.string(),
  address: nullableText, addressDetail: nullableText,
  lat: z.number().min(-90).max(90), lng: z.number().min(-180).max(180),
  busiId: nullableText, orgName: nullableText, operatorName: nullableText, operatorCall: nullableText,
  useTime: nullableText, parkingFree: z.boolean().nullable(), limited: z.boolean().nullable(),
  limitDetail: nullableText, infoUpdatedAt: localDate,
  accesses: z.array(accessSchema), chargers: z.array(chargerSchema),
});

export const chargerHistorySchema = z.object({
  chargerId: z.number().int().positive(), from: z.string(), to: z.string(),
  size: z.number().int().min(1).max(500), nextCursor: nullableText,
  items: z.array(z.object({
    statusCode: z.string(), normalizedStatus: status, statusUpdatedAt: z.string(),
    lastChargeStart: localDate, lastChargeEnd: localDate, nowChargeStart: localDate,
    sourceApi: z.enum(['INFO', 'STATUS']), collectedAt: z.string(),
  })),
});

export type StationDetail = z.infer<typeof stationDetailSchema>;
export type ChargerHistory = z.infer<typeof chargerHistorySchema>;
export interface HistoryQuery { chargerId: number; from: string; to: string; size?: number; cursor?: string }

export function validateId(id: number, label: string) {
  if (!Number.isSafeInteger(id) || id < 1) throw new Error(`${label} 번호가 올바르지 않습니다.`);
}

export function validateHistoryQuery(query: HistoryQuery) {
  validateId(query.chargerId, '충전기');
  const from = Date.parse(`${query.from}+09:00`);
  const to = Date.parse(`${query.to}+09:00`);
  if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from || to - from > 7 * 86400000) throw new Error('조회 기간은 7일 이내로 선택해 주세요.');
  if (query.size !== undefined && (!Number.isInteger(query.size) || query.size < 1 || query.size > 500)) throw new Error('조회 건수는 1~500이어야 합니다.');
}
