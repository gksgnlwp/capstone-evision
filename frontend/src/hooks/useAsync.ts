import { useCallback, useEffect, useState } from 'react';

export type AsyncState<T> =
  | { status: 'loading'; data?: undefined; error?: undefined }
  | { status: 'success'; data: T; error?: undefined }
  | { status: 'error'; data?: undefined; error: Error };

/** loader는 useCallback 또는 모듈 함수로 안정화해서 전달합니다. 화면 이탈/재조회 시 이전 요청을 취소합니다. */
export function useAsync<T>(loader: (signal: AbortSignal) => Promise<T>) {
  const [revision, setRevision] = useState(0);
  const [snapshot, setSnapshot] = useState<{ loader: typeof loader; revision: number; state: AsyncState<T> }>({
    loader, revision: 0, state: { status: 'loading' },
  });
  if (snapshot.loader !== loader || snapshot.revision !== revision) {
    // 조건이나 재시도가 바뀐 렌더에서 이전 데이터를 표시하지 않도록 초기화합니다.
    setSnapshot({ loader, revision, state: { status: 'loading' } });
  }
  const retry = useCallback(() => setRevision(value => value + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    Promise.resolve().then(() => loader(controller.signal)).then(
      data => { if (active) setSnapshot({ loader, revision, state: { status: 'success', data } }); },
      error => { if (active) setSnapshot({ loader, revision, state: { status: 'error', error: error instanceof Error ? error : new Error('조회에 실패했습니다.') } }); },
    );
    return () => { active = false; controller.abort(); };
  }, [loader, revision]);

  return { ...snapshot.state, retry };
}
