import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../services/api';
import { createMockClient } from '../../services/api/mockClient';
import { stationHandlers } from './stationFixtures';
import { createStationService } from './stationService';

const service = createStationService(createMockClient(stationHandlers, 0));
const period = { chargerId: 10401, from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' };

describe('충전소 상세·충전기 이력 계약', () => {
  it('상세 응답에서 충전기 원천 상태와 UNKNOWN을 구분한다', async () => {
    const detail = await service.detail(108);
    expect(detail.stationId).toBe(108);
    expect(detail.accesses).toHaveLength(2);
    expect(detail.chargers.every(charger => charger.normalizedStatus === 'UNKNOWN' && charger.statusCode === null)).toBe(true);
  });
  it('상세와 이력 요청의 실제 백엔드 경로·쿼리를 사용한다', async () => {
    const detail = await service.detail(104);
    const history = await service.history(period);
    const request = vi.fn().mockResolvedValueOnce(detail).mockResolvedValueOnce(history);
    const live = createStationService({ request } as ApiClient);
    await live.detail(104);
    await live.history({ ...period, size: 20, cursor: '2026-01-01T12:00:00' });
    expect(request.mock.calls[0][0]).toBe('stations/104');
    expect(request.mock.calls[0][1]).toMatchObject({ credentials: 'omit' });
    expect(request.mock.calls[1][0]).toBe('chargers/10401/history');
    expect(request.mock.calls[1][1]).toMatchObject({ query: { from: period.from, to: period.to, size: 20, cursor: '2026-01-01T12:00:00' } });
  });
  it('최대 7일 기간과 유효한 충전기 번호를 요구한다', async () => {
    await expect(service.history({ ...period, chargerId: 0 })).rejects.toThrow('충전기 번호');
    await expect(service.history({ ...period, to: '2026-01-09T00:00:00' })).rejects.toThrow('7일');
    await expect(service.detail(-1)).rejects.toThrow('충전소 번호');
  });
  it('커서로 다음 상태 변경 페이지를 중복 없이 읽는다', async () => {
    const now = Date.now();
    const from = new Date(now - 86400000 + 9 * 3600000).toISOString().slice(0, 19);
    const to = new Date(now + 9 * 3600000).toISOString().slice(0, 19);
    const first = await service.history({ chargerId: 10401, from, to, size: 20 });
    expect(first.items).toHaveLength(20);
    expect(first.nextCursor).not.toBeNull();
    const second = await service.history({ chargerId: 10401, from, to, size: 20, cursor: first.nextCursor! });
    expect(second.items).toHaveLength(5);
    expect(second.nextCursor).toBeNull();
    expect(new Set([...first.items, ...second.items].map(item => item.statusUpdatedAt)).size).toBe(25);
  });
  it('필수 상세 필드가 없으면 성공 결과로 취급하지 않는다', async () => {
    const invalid = createStationService({ request: vi.fn().mockResolvedValue({ stationId: 104 }) } as ApiClient);
    await expect(invalid.detail(104)).rejects.toMatchObject({ kind: 'invalid-response' });
  });
});
