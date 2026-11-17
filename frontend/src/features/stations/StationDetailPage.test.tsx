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
  it('완속충전기를 제외하고 오류 충전기·수집 지연을 알린다', async () => {
    const detail = await createStationService(createMockClient(stationHandlers, 0)).detail(104);
    const oldStatusTime = localKst(new Date(Date.now() - 11 * 60 * 1000));
    const chargers = detail.chargers.slice(0, 2).map(charger => ({
      ...charger, normalizedStatus: 'UNAVAILABLE' as const, reliability: 'FAULT' as const, statusUpdatedAt: oldStatusTime,
    }));
    const slow = { ...detail.chargers[2], chargerId: 99999, chgerId: '완속', fast: false };
    vi.spyOn(stationService, 'detail').mockResolvedValue({ ...detail, reliability: 'ALL_ERROR', statusCollectedAt: oldStatusTime, chargers: [...chargers, slow] });

    render(<MemoryRouter initialEntries={['/stations/104']}><Routes><Route path="/stations/:stationId" element={<StationDetailPage />} /></Routes></MemoryRouter>);

    expect(await screen.findByRole('alert')).toHaveTextContent('정상으로 확인된 급속충전기가 없어요');
    expect(screen.getByRole('link', { name: '다른 충전소 찾기' })).toHaveAttribute('href', '/route');
    expect(screen.getByRole('status')).toHaveTextContent('마지막으로 성공한 상태 수집이 10분 이상 지났습니다');
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuemax', '2');
    expect(within(screen.getByRole('region', { name: '충전기 상태' })).queryByText('충전기 완속')).not.toBeInTheDocument();
  });
  it('의심 충전기는 원천 상태가 사용 가능이어도 이용 가능 대수에서 제외한다', async () => {
    const detail = await createStationService(createMockClient(stationHandlers, 0)).detail(104);
    const chargers = detail.chargers.map((charger, index) => index === 0
      ? { ...charger, reliability: 'SUSPECT' as const, statusUpdatedAt: localKst(new Date(Date.now() - 8 * 86400000)) }
      : charger);
    vi.spyOn(stationService, 'detail').mockResolvedValue({ ...detail, reliability: 'PARTIAL', chargers });

    render(<MemoryRouter initialEntries={['/stations/104']}><Routes><Route path="/stations/:stationId" element={<StationDetailPage />} /></Routes></MemoryRouter>);

    expect(await screen.findByRole('meter')).toHaveAttribute('aria-valuenow', '2');
    expect(screen.getByText('상태 의심 1')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
