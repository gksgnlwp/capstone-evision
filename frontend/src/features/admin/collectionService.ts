import { z } from 'zod';
import { config } from '../../config/env';
import { ApiError, apiClient } from '../../services/api';
import { createMockClient } from '../../services/api/mockClient';

const localTime = z.string().datetime({ local: true });
const slotRange = z.object({ from: localTime, to: localTime, slots: z.number().int().nonnegative() });
export const collectionStatusSchema = z.object({
  from: localTime,
  to: localTime,
  status: z.enum(['OK', 'NO_RECORDS']),
  lastSuccess: z.object({ status: localTime.nullable(), info: localTime.nullable() }),
  runs: z.object({ total: z.number().int().nonnegative(), byStatus: z.record(z.string(), z.number().int().nonnegative()) }),
  counts: z.object({ fetched: z.number(), inserted: z.number(), duplicate: z.number(), unknown: z.number(), invalid: z.number() }),
  apiCallsToday: z.object({ used: z.number(), limit: z.number() }),
  availability: z.object({ scheduledSlots: z.number(), successfulSlots: z.number(), slotRate: z.number().nullable(), scheduledWindows: z.number(), completeWindows: z.number(), windowRate: z.number().nullable() }),
  maxConsecutiveFailures: z.number().int().nonnegative(),
  consecutiveFailures: z.array(slotRange),
  missingRanges: z.array(slotRange),
  disk: z.object({ totalBytes: z.number(), usableBytes: z.number(), usedRate: z.number(), warning: z.boolean() }),
});
export type CollectionStatus = z.infer<typeof collectionStatusSchema>;

export function kstLocalTime(date: Date) {
  return new Date(date.getTime() + 9 * 60 * 60 * 1000).toISOString().slice(0, 19);
}

const mock = createMockClient({
  'GET admin/collection-status': ({ query }) => {
    const to = String(query?.to ?? kstLocalTime(new Date()));
    const from = String(query?.from ?? kstLocalTime(new Date(Date.now() - 86400000)));
    const days = Math.max(1, Math.round((Date.parse(`${to}+09:00`) - Date.parse(`${from}+09:00`)) / 86400000));
    const scheduledSlots = days * 288;
    const successfulSlots = scheduledSlots - (days === 1 ? 2 : days * 3);
    const scheduledWindows = days * 48;
    const completeWindows = scheduledWindows - days;
    return {
      from, to, status: 'OK', lastSuccess: { status: to, info: kstLocalTime(new Date(Date.now() - 5 * 3600000)) },
      runs: { total: 289 * days, byStatus: { RUNNING: 0, SUCCESS: 286 * days, PARTIAL: days, FAILED: 2 * days } },
      counts: { fetched: 516200 * days, inserted: 14850 * days, duplicate: 501030 * days, unknown: 210 * days, invalid: 110 * days },
      apiCallsToday: { used: 308, limit: 1000 },
      availability: { scheduledSlots, successfulSlots, slotRate: successfulSlots / scheduledSlots, scheduledWindows, completeWindows, windowRate: completeWindows / scheduledWindows },
      maxConsecutiveFailures: 2,
      consecutiveFailures: [{ from: kstLocalTime(new Date(Date.now() - 20 * 60000)), to: kstLocalTime(new Date(Date.now() - 15 * 60000)), slots: 2 }],
      missingRanges: [{ from: kstLocalTime(new Date(Date.now() - 20 * 60000)), to: kstLocalTime(new Date(Date.now() - 15 * 60000)), slots: 2 }],
      disk: { totalBytes: 100000000000, usableBytes: 34200000000, usedRate: 0.658, warning: false },
    };
  },
});

export async function getCollectionStatus(from: string, to: string, signal?: AbortSignal) {
  const client = config.environment === 'DEV' ? mock : apiClient;
  const response = await client.request('admin/collection-status', { query: { from, to }, signal });
  const parsed = collectionStatusSchema.safeParse(response);
  if (!parsed.success) throw new ApiError('수집 현황 응답 형식을 확인할 수 없습니다.', 'invalid-response');
  return parsed.data;
}
