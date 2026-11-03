import { useCallback, useState } from 'react';
import { Check } from 'lucide-react';
import { Button, EmptyState, ErrorState, LoadingState } from '../../components/ui';
import { useAsync } from '../../hooks/useAsync';
import { personalizationService } from './personalizationService';
import { setPreferredOperators, usePreferences } from './preferences';

const loadOperators = (signal: AbortSignal) => personalizationService.operators(signal);

export function OperatorPage() {
  const { operatorIds, operatorPersistent } = usePreferences();
  const [selected, setSelected] = useState<string[]>(operatorIds);
  const [saved, setSaved] = useState(false);
  const result = useAsync(loadOperators);

  const toggle = useCallback((id: string) => {
    setSaved(false);
    setSelected(current => current.includes(id) ? current.filter(value => value !== id) : [...current, id]);
  }, []);
  const save = () => { setPreferredOperators(selected); setSaved(true); };

  return <div className="stack operator-page">
    <div><h1>선호 운영기관</h1><p className="small muted">보유한 회원카드 기관을 골라 주세요.</p></div>
    <p className="operator-guidance">선택한 기관의 충전소는 지도와 목록에서 강조됩니다. 다른 기관 충전소도 그대로 표시됩니다.</p>
    {result.status === 'loading' && <LoadingState label="운영기관을 불러오고 있습니다." />}
    {result.status === 'error' && <ErrorState message={result.error.message} onRetry={result.retry} />}
    {result.status === 'success' && (result.data.length === 0
      ? <EmptyState title="선택할 운영기관이 없어요" description="서비스 대상 충전소가 준비되면 표시됩니다." />
      : <div className="operator-list" role="group" aria-label="운영기관 목록">{result.data.map(operator => <label className="operator-row" key={operator.busiId}>
        <input type="checkbox" checked={selected.includes(operator.busiId)} onChange={() => toggle(operator.busiId)} />
        <span className="operator-check" aria-hidden="true"><Check size={13} strokeWidth={3} /></span>
        <span className="operator-name">{operator.name}</span><span className="operator-count">충전소 {operator.stationCount}곳</span>
      </label>)}</div>)}
    <p className="small muted">기관 코드·이름·충전소 수는 서비스 대상 충전소 기준입니다.</p>
    <p className="personalization-note">{operatorPersistent ? '선택값은 이 브라우저에 저장됩니다.' : '브라우저 저장소를 사용할 수 없어 선택값이 현재 접속 동안만 유지됩니다. 새로고침하면 사라질 수 있어요.'} 현재 선택값은 서버로 전송하지 않으며, 추천 순위의 기관 가중치는 서버 연동 후 적용됩니다.</p>
    <div className="operator-actions"><Button variant="secondary" onClick={() => { setSelected([]); setSaved(false); }} disabled={selected.length === 0}>모두 해제</Button><Button onClick={save} disabled={result.status !== 'success'}>저장 ({selected.length}곳 선택)</Button></div>
    {saved && <p role="status" className="small operator-saved">{operatorPersistent ? '선호 운영기관을 이 브라우저에 저장했습니다.' : '선호 운영기관을 현재 접속 동안 적용했습니다.'}</p>}
  </div>;
}
