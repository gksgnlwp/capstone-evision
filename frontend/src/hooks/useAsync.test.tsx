import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useAsync } from './useAsync';

describe('화면 비동기 조회', () => {
  it('조건 변경 시 취소된 이전 응답이 최신 결과를 덮어쓰지 않는다', async () => {
    let resolveOld!: (value: string) => void;
    let oldSignal!: AbortSignal;
    const oldLoader = (signal: AbortSignal) => {
      oldSignal = signal;
      return new Promise<string>(resolve => { resolveOld = resolve; });
    };
    const newLoader = async () => '새 결과';
    const { result, rerender } = renderHook(({ loader }) => useAsync(loader), { initialProps: { loader: oldLoader } });
    await waitFor(() => expect(oldSignal).toBeDefined());
    rerender({ loader: newLoader });
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(oldSignal.aborted).toBe(true);
    await act(async () => { resolveOld('오래된 결과'); });
    expect(result.current.data).toBe('새 결과');
  });

  it('실패 후 재시도하면 새 요청의 결과를 표시한다', async () => {
    let attempt = 0;
    const loader = async () => { if (++attempt === 1) throw new Error('첫 요청 실패'); return '복구'; };
    const { result } = renderHook(() => useAsync(loader));
    await waitFor(() => expect(result.current.status).toBe('error'));
    act(() => result.current.retry());
    await waitFor(() => expect(result.current.data).toBe('복구'));
  });
});
