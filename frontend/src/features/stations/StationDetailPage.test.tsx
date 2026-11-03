import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { createMockClient } from '../../services/api/mockClient';
import { StationDetailPage } from './StationDetailPage';
import { stationHandlers } from './stationFixtures';
import { createStationService, stationService } from './stationService';
import { localKst } from './stationUtils';

afterEach(() => vi.restoreAllMocks());

describe('충전소 상세의 급속충전기 경고', () => {
  it('완속충전기를 제외하고 전체 이용 불가·갱신 지연을 알린다', async () => {
    const detail = await createStationService(createMockClient(stationHandlers, 0)).detail(104);
    const oldStatusTime = localKst(new Date(Date.now() - 11 * 60 * 1000));
    const chargers = detail.chargers.slice(0, 2).map(charger => ({
      ...charger, normalizedStatus: 'UNAVAILABLE' as const, statusUpdatedAt: oldStatusTime,
    }));
    const slow = { ...detail.chargers[2], chargerId: 99999, chgerId: '완속', fast: false };
    vi.spyOn(stationService, 'detail').mockResolvedValue({ ...detail, chargers: [...chargers, slow] });

    render(<MemoryRouter initialEntries={['/stations/104']}><Routes><Route path="/stations/:stationId" element={<StationDetailPage />} /></Routes></MemoryRouter>);

    expect(await screen.findByRole('alert')).toHaveTextContent('모든 급속충전기가 이용 불가');
    expect(screen.getByRole('link', { name: '다른 충전소 찾기' })).toHaveAttribute('href', '/route');
    expect(screen.getByRole('status')).toHaveTextContent('2대의 상태가 10분 이상 갱신되지 않았습니다');
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuemax', '2');
    expect(within(screen.getByRole('region', { name: '충전기 상태' })).queryByText('충전기 완속')).not.toBeInTheDocument();
  });
});
